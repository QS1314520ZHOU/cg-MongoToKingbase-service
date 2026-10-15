-- =============================================================================
-- TB_YXPG_PGJLHZ  评估结论汇总视图
-- =============================================================================
-- 数据源：Kingbase SmartCare.score（从 MongoDB SmartCare.score 同步）
-- 关联：score.pid = patient.mongo_id
-- 规则：s.valid IS NOT FALSE（保留 true 与 NULL；排除 false 失效记录）
--
-- 涵盖 16 种评估项目（位置1的 10 项 + 位置2独有的 6 项；镇静唤醒无数据源已排除）：
--   gcsScore, bradenScore, selfCareAbility, commitSuicideScore,
--   incontinenceScore, patientFallDangerLJRMYY, unPlannedCGZYYScore,
--   toleranceScoreV2, toothScore, painScore, rass, deliriumScore,
--   mechanicalAspirationRisk, wst, patientBodyConstraintTransfer,
--   criticalPatientTransferScore
-- 另含 apacheII（与 TB_YXPG_JXSLYMXJKPFB 详细视图互补）
--
-- 评估项目编码优先采用国际标准（LOINC），无标准编码的用拼音码：
--   9269-2(GCS) 38227-5(Braden) 96761-2(Barthel) 9264-3(APACHE II)
--   Morse / NGASR / IAD / RASS / CAMICU / WST / MEWS 行业量表简称
--   CGZHY/RCNYYNS/YCPG/CPT_NRS/WXFX/YSPG 拼音首字母
--
-- 风险等级生成规则（无标准映射的项输出 '--'）：
--   Braden: 15-18无 / 13-14低 / 10-12中 / 6-9高 / ≤6极高
--   GCS:    13-15低 / 9-12中 / 3-8高 / 否则极高
--   Barthel:100无 / 61-99低 / 41-60中 / ≤40高
--   NGASR:  0-5低 / 6-8中 / 9-11高 / ≥12极高
--   Morse:  <25无 / 25-44低 / ≥45高
--   NRS:    0无 / 1-3低 / 4-6中 / 7-10高
--   MEWS:   <5低 / 5-6中 / 7-8高 / ≥9极高
--   CAM-ICU:0无 / ≥3高（阳性）
--   APACHE II:≤9无 / ≤14低 / ≤19中 / ≤24高 / ≥25极高
--
-- 牙齿评估 CPGJG_WB 拼接规则：
--   abnormal=true  → '无异常'
--   abnormal=false → 按"上排左/上排右/下排左/下排右"分区，相同值连续牙位合并为
--                     "1-8"（区间），单牙位 "1"，用"；"分隔，例如：
--                     "上排左4-6：固定牙齿；上排右1：松动"
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 辅助函数：牙齿评估 toothMap 拼接
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_format_tooth_map(p_toothmap jsonb) RETURNS text AS $$
DECLARE
  v_value text;
  v_prefix text;
  v_num int;
  cur_prefix text := '';
  cur_value text := '';
  cur_start int := 0;
  cur_end int := 0;
  result text := '';
  has_open boolean := false;
  rec record;
BEGIN
  IF p_toothmap IS NULL OR p_toothmap = '{}'::jsonb THEN
    RETURN NULL;
  END IF;

  FOR rec IN
    SELECT
      jt.key AS k,
      jt.value AS v,
      regexp_replace(jt.key, '\d+$', '') AS prefix,
      (regexp_match(jt.key, '\d+'))[1]::int AS num
    FROM jsonb_each_text(p_toothmap) jt
    WHERE jt.key ~ '\d+$'
    ORDER BY
      CASE
        WHEN jt.key LIKE '上排左%' THEN 1
        WHEN jt.key LIKE '上排右%' THEN 2
        WHEN jt.key LIKE '下排左%' THEN 3
        WHEN jt.key LIKE '下排右%' THEN 4
        ELSE 5
      END,
      (regexp_match(jt.key, '\d+'))[1]::int
  LOOP
    v_value := trim(rec.v);
    CONTINUE WHEN v_value = '';  -- 跳过空值

    v_prefix := rec.prefix;
    v_num := rec.num;

    IF has_open
       AND v_prefix = cur_prefix
       AND v_value  = cur_value
       AND v_num    = cur_end + 1 THEN
      cur_end := v_num;  -- 扩展区间
    ELSE
      -- 关闭上一段
      IF has_open THEN
        result := result
          || CASE WHEN result = '' THEN '' ELSE '；' END
          || cur_prefix
          || CASE WHEN cur_start = cur_end
                  THEN cur_start::text
                  ELSE cur_start::text || '-' || cur_end::text END
          || '：' || cur_value;
      END IF;
      cur_prefix := v_prefix;
      cur_value  := v_value;
      cur_start  := v_num;
      cur_end    := v_num;
      has_open   := true;
    END IF;
  END LOOP;

  IF has_open THEN
    result := result
      || CASE WHEN result = '' THEN '' ELSE '；' END
      || cur_prefix
      || CASE WHEN cur_start = cur_end
              THEN cur_start::text
              ELSE cur_start::text || '-' || cur_end::text END
      || '：' || cur_value;
  END IF;

  RETURN NULLIF(result, '');
END;
$$ LANGUAGE plpgsql IMMUTABLE;


-- -----------------------------------------------------------------------------
-- 2. 主视图
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW tb_yxpg_pgjlhz AS
SELECT
  'H50010400021'::text                                       AS cyljgdm,        -- 医疗机构代码
  '重钢总医院'::text                                         AS cyljgmc,        -- 医疗机构名称
  s."_id"::text                                              AS cjlid,          -- 记录ID
  '3'::text                                                  AS chzlxdm,        -- 患者类型代码 3=住院
  '住院'::text                                               AS chzlxmc,
  p.mrn::text                                                AS cjzh,           -- 就诊号=住院号
  p.hispid::text                                             AS chzbs,          -- 患者标识=hisID
  p.name::text                                               AS chzxm,          -- 患者姓名
  NULL::text                                                 AS cbqdm,          -- 病区代码（无）
  NULL::text                                                 AS cbqmc,          -- 病区名称
  p.deptcode::text                                           AS cksdm,          -- 科室代码
  p.dept::text                                               AS cksmc,          -- 科室名称
  CASE WHEN p.icuadmissiontime IS NOT NULL AND p.icudischargetime IS NULL
       THEN 1 ELSE 0 END::smallint                            AS bzy,            -- 在院标志

  -- 评估项目编码
  CASE s.scoretype
    WHEN 'gcsScore'                       THEN '9269-2'
    WHEN 'bradenScore'                    THEN '38227-5'
    WHEN 'selfCareAbility'                THEN '96761-2'
    WHEN 'apacheII'                       THEN '9264-3'
    WHEN 'commitSuicideScore'             THEN 'NGASR'
    WHEN 'incontinenceScore'              THEN 'IAD'
    WHEN 'patientFallDangerLJRMYY'        THEN 'Morse'
    WHEN 'unPlannedCGZYYScore'            THEN 'CGZHY'
    WHEN 'toleranceScoreV2'               THEN 'RCNYYNS'
    WHEN 'toothScore'                     THEN 'YCPG'
    WHEN 'painScore'                      THEN 'CPT_NRS'
    WHEN 'rass'                           THEN 'RASS'
    WHEN 'deliriumScore'                  THEN 'CAMICU'
    WHEN 'mechanicalAspirationRisk'       THEN 'WXFX'
    WHEN 'wst'                            THEN 'WST'
    WHEN 'patientBodyConstraintTransfer'  THEN 'YSPG'
    WHEN 'criticalPatientTransferScore'   THEN 'MEWS'
    ELSE s.scoretype
  END::text                                                  AS cpgxmbm,

  -- 评估项目名称（沿用界面文案）
  CASE s.scoretype
    WHEN 'gcsScore'                       THEN 'Glasgow昏迷量表(GCS)'
    WHEN 'bradenScore'                    THEN '压力性损伤评估及措施记录单'
    WHEN 'selfCareAbility'                THEN '日常生活能力评估单'
    WHEN 'commitSuicideScore'             THEN '自杀风险(NGASR)评估'
    WHEN 'incontinenceScore'              THEN '失禁性皮炎分类及会阴部皮肤评估护理记录单'
    WHEN 'patientFallDangerLJRMYY'        THEN '跌倒/坠床风险评估及预防措施记录单'
    WHEN 'unPlannedCGZYYScore'            THEN '非计划拔管风险评估及护理措施记录单'
    WHEN 'toleranceScoreV2'               THEN '肠内营养耐受评分'
    WHEN 'toothScore'                     THEN '牙齿评估'
    WHEN 'painScore'                      THEN '疼痛评估'
    WHEN 'rass'                           THEN '镇静评分'
    WHEN 'deliriumScore'                  THEN '谵妄评分'
    WHEN 'mechanicalAspirationRisk'       THEN '误吸风险评估'
    WHEN 'wst'                            THEN '吞咽风险评估'
    WHEN 'patientBodyConstraintTransfer'  THEN '约束评分表'
    WHEN 'criticalPatientTransferScore'   THEN '转运风险评估'
    WHEN 'apacheII'                       THEN '急性生理与慢性健康评分表'
  END::text                                                  AS cpgxmmc,

  -- 结果类型代码：PQ 数值 / CD 文本 / PN 阴阳
  CASE
    WHEN s.scoretype IN ('toothScore', 'wst', 'apacheII', 'criticalPatientTransferScore', 'deliriumScore') THEN 'CD'
    ELSE 'PQ'
  END::text                                                  AS cjglxdm,

  -- 评估结果_数值
  CASE s.scoretype
    WHEN 'gcsScore'                       THEN s.total
    WHEN 'bradenScore'                    THEN s.total
    WHEN 'selfCareAbility'                THEN s.total
    WHEN 'commitSuicideScore'             THEN s.total
    WHEN 'incontinenceScore'              THEN s.total
    WHEN 'patientFallDangerLJRMYY'        THEN s.total
    WHEN 'unPlannedCGZYYScore'            THEN s.total
    WHEN 'painScore'                      THEN s.total
    WHEN 'deliriumScore'                  THEN s.total
    WHEN 'mechanicalAspirationRisk'       THEN s.total
    WHEN 'patientBodyConstraintTransfer'  THEN s.total
    WHEN 'apacheII'                       THEN (s.apacheii::jsonb ->> 'totalScore')::numeric
    WHEN 'rass'                           THEN s.rass::numeric
    WHEN 'wst'                            THEN s.wstindex
    WHEN 'criticalPatientTransferScore'   THEN (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal')::numeric
    WHEN 'toleranceScoreV2'               THEN s.total
    WHEN 'toothScore'                     THEN NULL
  END::numeric                                               AS npgjg_sz,

  -- 评估结果_文本
  CASE s.scoretype
    WHEN 'toothScore' THEN
      CASE
        WHEN COALESCE((s.toothscore::jsonb ->> 'abnormal')::boolean, true) THEN '无异常'
        ELSE fn_format_tooth_map(s.toothscore::jsonb -> 'toothMap')
      END
    WHEN 'wst'                            THEN s.wstscore
    WHEN 'apacheII'                       THEN s.apacheii::jsonb ->> 'conclusion'
    WHEN 'criticalPatientTransferScore'   THEN s.criticalpatienttransferscore::jsonb ->> 'conclusion'
    WHEN 'deliriumScore'                  THEN s.deliriumscore::jsonb ->> 'conclusion'
    WHEN 'painScore'                      THEN s.conclusion
    WHEN 'bradenScore'                    THEN s.conclusion
    WHEN 'incontinenceScore'              THEN s.conclusion
    WHEN 'mechanicalAspirationRisk'       THEN s.conclusion
    WHEN 'selfCareAbility'                THEN s.conclusion
    WHEN 'commitSuicideScore'             THEN s.conclusion
    WHEN 'patientFallDangerLJRMYY'        THEN s.conclusion
    WHEN 'unPlannedCGZYYScore'            THEN s.conclusion
    WHEN 'toleranceScoreV2'               THEN s.conclusion
    WHEN 'gcsScore'                       THEN s.conclusion
    WHEN 'rass'                           THEN NULL
    WHEN 'patientBodyConstraintTransfer'  THEN NULL
  END::text                                                  AS cpgjg_wb,

  -- 评估结果_阴阳（本视图无阴阳型评估，留空）
  NULL::text                                                 AS cpgjg_yy,

  -- 风险等级编码
  CASE
    WHEN s.scoretype = 'gcsScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total BETWEEN 13 AND 15 THEN '1'
           WHEN s.total BETWEEN  9 AND 12 THEN '2'
           WHEN s.total BETWEEN  3 AND  8 THEN '3'
           ELSE '4' END
    WHEN s.scoretype = 'bradenScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total BETWEEN 15 AND 18 THEN '0'
           WHEN s.total BETWEEN 13 AND 14 THEN '1'
           WHEN s.total BETWEEN 10 AND 12 THEN '2'
           WHEN s.total BETWEEN  6 AND  9 THEN '3'
           ELSE '4' END
    WHEN s.scoretype = 'selfCareAbility' AND s.total IS NOT NULL THEN
      CASE WHEN s.total = 100               THEN '0'
           WHEN s.total >= 61               THEN '1'
           WHEN s.total >= 41               THEN '2'
           ELSE '3' END
    WHEN s.scoretype = 'commitSuicideScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total <=  5 THEN '1'
           WHEN s.total <=  8 THEN '2'
           WHEN s.total <= 11 THEN '3'
           ELSE '4' END
    WHEN s.scoretype = 'patientFallDangerLJRMYY' AND s.total IS NOT NULL THEN
      CASE WHEN s.total <  25 THEN '0'
           WHEN s.total <  45 THEN '1'
           ELSE '3' END
    WHEN s.scoretype = 'painScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total =  0  THEN '0'
           WHEN s.total <= 3  THEN '1'
           WHEN s.total <= 6  THEN '2'
           ELSE '3' END
    WHEN s.scoretype = 'criticalPatientTransferScore'
         AND (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal') IS NOT NULL THEN
      CASE WHEN (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal')::numeric <  5 THEN '1'
           WHEN (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal')::numeric <  7 THEN '2'
           WHEN (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal')::numeric <  9 THEN '3'
           ELSE '4' END
    WHEN s.scoretype = 'deliriumScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total = 0 THEN '0' ELSE '3' END  -- CAM-ICU 阳性
    WHEN s.scoretype = 'apacheII'
         AND (s.apacheii::jsonb ->> 'totalScore') IS NOT NULL THEN
      CASE WHEN (s.apacheii::jsonb ->> 'totalScore')::numeric <=  9 THEN '0'
           WHEN (s.apacheii::jsonb ->> 'totalScore')::numeric <= 14 THEN '1'
           WHEN (s.apacheii::jsonb ->> 'totalScore')::numeric <= 19 THEN '2'
           WHEN (s.apacheii::jsonb ->> 'totalScore')::numeric <= 24 THEN '3'
           ELSE '4' END
    ELSE '--'
  END::text                                                  AS cfxdjbm,

  -- 风险等级名称
  CASE
    WHEN s.scoretype = 'gcsScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total BETWEEN 13 AND 15 THEN '低风险'
           WHEN s.total BETWEEN  9 AND 12 THEN '中风险'
           WHEN s.total BETWEEN  3 AND  8 THEN '高风险'
           ELSE '极高风险' END
    WHEN s.scoretype = 'bradenScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total BETWEEN 15 AND 18 THEN '无风险'
           WHEN s.total BETWEEN 13 AND 14 THEN '低风险'
           WHEN s.total BETWEEN 10 AND 12 THEN '中风险'
           WHEN s.total BETWEEN  6 AND  9 THEN '高风险'
           ELSE '极高风险' END
    WHEN s.scoretype = 'selfCareAbility' AND s.total IS NOT NULL THEN
      CASE WHEN s.total = 100              THEN '无风险'
           WHEN s.total >= 61              THEN '低风险'
           WHEN s.total >= 41              THEN '中风险'
           ELSE '高风险' END
    WHEN s.scoretype = 'commitSuicideScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total <=  5 THEN '低风险'
           WHEN s.total <=  8 THEN '中风险'
           WHEN s.total <= 11 THEN '高风险'
           ELSE '极高风险' END
    WHEN s.scoretype = 'patientFallDangerLJRMYY' AND s.total IS NOT NULL THEN
      CASE WHEN s.total < 25 THEN '无风险'
           WHEN s.total < 45 THEN '低风险'
           ELSE '高风险' END
    WHEN s.scoretype = 'painScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total =  0 THEN '无风险'
           WHEN s.total <= 3 THEN '低风险'
           WHEN s.total <= 6 THEN '中风险'
           ELSE '高风险' END
    WHEN s.scoretype = 'criticalPatientTransferScore'
         AND (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal') IS NOT NULL THEN
      CASE WHEN (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal')::numeric <  5 THEN '低风险'
           WHEN (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal')::numeric <  7 THEN '中风险'
           WHEN (s.criticalpatienttransferscore::jsonb ->> 'mewsTotal')::numeric <  9 THEN '高风险'
           ELSE '极高风险' END
    WHEN s.scoretype = 'deliriumScore' AND s.total IS NOT NULL THEN
      CASE WHEN s.total = 0 THEN '无风险' ELSE '高风险' END
    WHEN s.scoretype = 'apacheII'
         AND (s.apacheii::jsonb ->> 'totalScore') IS NOT NULL THEN
      CASE WHEN (s.apacheii::jsonb ->> 'totalScore')::numeric <=  9 THEN '无风险'
           WHEN (s.apacheii::jsonb ->> 'totalScore')::numeric <= 14 THEN '低风险'
           WHEN (s.apacheii::jsonb ->> 'totalScore')::numeric <= 19 THEN '中风险'
           WHEN (s.apacheii::jsonb ->> 'totalScore')::numeric <= 24 THEN '高风险'
           ELSE '极高风险' END
    ELSE '--'
  END::text                                                  AS cfxdjmc,

  -- 备注
  NULL::text                                                 AS cbz,

  -- 评估日期时间
  s."time"                                                   AS dpgrqsj,
  s.inputuserid::text                                        AS cpgrdm,
  s.inputuser::text                                          AS cpgrxm,

  -- 取消标志：valid 缺失或 true → 0(有效)；valid=false → 1(无效)
  CASE WHEN s.valid IS NOT FALSE THEN 0 ELSE 1 END::smallint AS bqx,

  -- 数据创建时间 / 增量时间
  s."time"                                                   AS dsjcjsj,
  s.mongotokingdate                                          AS dsjzlsj,

  -- 数据状态：valid 缺失或 true → 1(有效)；valid=false → 0(无效)
  CASE WHEN s.valid IS NOT FALSE THEN 1 ELSE 0 END::smallint AS bsjzt

FROM score s
LEFT JOIN patient p ON s.pid = p.mongo_id
WHERE s.valid IS NOT FALSE;
