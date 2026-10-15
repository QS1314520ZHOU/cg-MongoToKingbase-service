-- =====================================================
-- 急性生理与慢性健康评分表视图 (TB_YXPG_JXSLYMXJKPFB / APACHE II)
-- 数据来源：Kingbase score 表（scoretype='apacheII'，嵌套明细在 apacheii JSON 列中）
--           LEFT JOIN patient（score.pid = patient.mongo_id，即 Mongo _id 字符串）
-- 字段标准：ICU视图新增部分.xlsx - Sheet「急性生理与慢性健康评分表」（58字段）
-- GCS反应名称映射：按评估界面文案（截图2）
--   睁眼: 1不睁眼 2刺痛睁眼 3呼吸睁眼 4正常睁眼
--   语言: 1无反应 2只能发音 3词句不清 4回答错乱 5回答正确
--   运动: 1无反应 2刺痛肢体伸直 3刺痛肢体屈曲 4刺痛能逃避 5刺痛能定位 6按吩咐动作
-- 生成时间：2026-09-08
-- =====================================================

CREATE OR REPLACE VIEW TB_YXPG_JXSLYMXJKPFB AS
SELECT
    -- 医疗机构代码（平台注册机构代码，同 TB_ICU 系列先例）
    'H50010400021'                              AS CYLJGDM,
    -- 医疗机构名称
    '重钢总医院'                                AS CYLJGMC,
    -- 记录ID（联合主键，取score.mongo_id）
    s.mongo_id                                  AS CJLID,
    -- 住院号
    p.mrn                                       AS CZYH,
    -- 患者标识（患者主索引）
    p.hispid                                    AS CHZBS,
    -- 患者姓名
    p.name                                      AS CHZXM,
    -- 出生日期
    p.birthday                                  AS DCSRQ,
    -- 性别代码（Male->1, Female->2）
    CASE
        WHEN p.gender = 'Male'   THEN '1'
        WHEN p.gender = 'Female' THEN '2'
        ELSE NULL
    END                                         AS CXBDM,
    -- 性别名称
    CASE
        WHEN p.gender = 'Male'   THEN '男'
        WHEN p.gender = 'Female' THEN '女'
        ELSE p.gender
    END                                         AS CXBMC,
    -- 年龄（岁）（评分表单录入的年龄）
    CAST(CAST(s.a #>> '{age,value}' AS numeric) AS int)     AS INLS,
    -- 病区代码 / 病区名称（ICU病区）
    p.deptcode                                  AS CBQDM,
    p.dept                                      AS CBQMC,
    -- 科室代码 / 科室名称
    p.deptcode                                  AS CKSDM,
    p.dept                                      AS CKSMC,
    -- 疾病诊断编码（临床诊断编码列表取第一个，同 TB_ICU_HZJSJL 先例）
    CASE
        WHEN p.clinicaldiagnosiscodelist IS NOT NULL
             AND p.clinicaldiagnosiscodelist != '[]'
        THEN SPLIT_PART(
            REPLACE(REPLACE(REPLACE(p.clinicaldiagnosiscodelist, '[', ''), ']', ''), '"', ''),
            ',', 1)
        ELSE NULL
    END                                         AS CJBZDBM,
    -- 疾病诊断名称
    p.clinicaldiagnosis                         AS CJBZDMC,
    -- 年龄评分（0/2/3/5/6）
    CAST(s.a #>> '{age,score}' AS smallint)     AS INLPF,
    -- 体温（℃），保留1位小数（界面显示口径，如 23.899999... → 23.9）
    ROUND(CAST(s.a #>> '{temp,value}' AS numeric), 1)       AS NTW,
    -- 平均动脉血压
    CAST(s.a #>> '{bp,value}' AS numeric)       AS NPJDMXY,
    -- 心率（次/min）
    CAST(CAST(s.a #>> '{hr,value}' AS numeric) AS int)      AS IXL,
    -- 呼吸频率（次/min）
    CAST(CAST(s.a #>> '{rr,value}' AS numeric) AS int)      AS IHXPL,
    -- 吸入氧浓度百分比 FiO2（%），保留2位小数
    ROUND(CAST(s.a #>> '{fio2,fio2,value}' AS numeric), 2)  AS IXRYNDBFB,
    -- 血氧分压 PaO2（FiO2<50%时），保留2位小数
    ROUND(CAST(s.a #>> '{fio2,paO2,value}' AS numeric), 2)  AS IXYFY,
    -- 肺泡动脉氧分压差 A-aDO2（FiO2>=50%时），保留2位小数
    ROUND(CAST(s.a #>> '{fio2,aaDO2,value}' AS numeric), 2) AS IFPDMYFYC,
    -- 动脉PH最大值 / 最小值（源数据为单一测量值，两端同填），保留2位小数
    ROUND(CAST(s.a #>> '{ph,value}' AS numeric), 2)         AS NDMPHZDZ,
    ROUND(CAST(s.a #>> '{ph,value}' AS numeric), 2)         AS NDMPHZXZ,
    -- 静脉碳酸氢根 HCO3 最大值 / 最小值，保留2位小数
    ROUND(CAST(s.a #>> '{hco3,value}' AS numeric), 2)       AS NJMTSQGZDZ,
    ROUND(CAST(s.a #>> '{hco3,value}' AS numeric), 2)       AS NJMTSQGZXZ,
    -- 血钠最大值 / 最小值，保留1位小数
    ROUND(CAST(s.a #>> '{na,value}' AS numeric), 1)         AS NXNZDZ,
    ROUND(CAST(s.a #>> '{na,value}' AS numeric), 1)         AS NXNZXZ,
    -- 血钾最大值 / 最小值，保留1位小数
    ROUND(CAST(s.a #>> '{k,value}' AS numeric), 1)          AS NXJZDZ,
    ROUND(CAST(s.a #>> '{k,value}' AS numeric), 1)          AS NXJZXZ,
    -- 血液肌酐
    CAST(s.a #>> '{cr,value}' AS numeric)       AS NXYJG,
    -- 是否急性肾功能衰竭（isTurbo: 0否 1是）
    CASE WHEN (s.a #>> '{cr,isTurbo}') = 'true' THEN 1 ELSE 0 END AS BJXSGNSJ,
    -- 血细胞比容 HCT 最大值 / 最小值（源字段 yaji=血球压积），保留1位小数
    ROUND(CAST(s.a #>> '{yaji,value}' AS numeric), 1)       AS IXXBBRZDZ,
    ROUND(CAST(s.a #>> '{yaji,value}' AS numeric), 1)       AS IXXBBRZXZ,
    -- 白细胞计数最大值 / 最小值，保留2位小数
    ROUND(CAST(s.a #>> '{wbc,value}' AS numeric), 2)        AS NBXBJSZDZ,
    ROUND(CAST(s.a #>> '{wbc,value}' AS numeric), 2)        AS NBXBJSZXZ,
    -- 睁眼反应编码（GCS评分分值）/ 名称
    s.a #>> '{gcs,eyeScore,score}'              AS CZYFYBM,
    CASE CAST(s.a #>> '{gcs,eyeScore,score}' AS int)
        WHEN 4 THEN '正常睁眼'
        WHEN 3 THEN '呼吸睁眼'
        WHEN 2 THEN '刺痛睁眼'
        WHEN 1 THEN '不睁眼'
        ELSE NULL
    END                                         AS CZYFYMC,
    -- 语言反应编码 / 名称
    s.a #>> '{gcs,languageScore,score}'         AS CYYFYBM,
    CASE CAST(s.a #>> '{gcs,languageScore,score}' AS int)
        WHEN 5 THEN '回答正确'
        WHEN 4 THEN '回答错乱'
        WHEN 3 THEN '词句不清'
        WHEN 2 THEN '只能发音'
        WHEN 1 THEN '无反应'
        ELSE NULL
    END                                         AS CYYFYMC,
    -- 运动反应编码 / 名称
    s.a #>> '{gcs,sportScore,score}'            AS CYDFYBM,
    CASE CAST(s.a #>> '{gcs,sportScore,score}' AS int)
        WHEN 6 THEN '按吩咐动作'
        WHEN 5 THEN '刺痛能定位'
        WHEN 4 THEN '刺痛能逃避'
        WHEN 3 THEN '刺痛肢体屈曲'
        WHEN 2 THEN '刺痛肢体伸直'
        WHEN 1 THEN '无反应'
        ELSE NULL
    END                                         AS CYDFYMC,
    -- 急性生理评分（12项APS小计 = 总分 - 年龄评分 - 慢性健康评分）
    CAST(
        CAST(s.a #>> '{totalScore}' AS numeric)
        - COALESCE(CAST(s.a #>> '{age,score}' AS numeric), 0)
        - COALESCE(CAST(s.a #>> '{manXing,score}' AS numeric), 0)
        AS int)                                 AS IJXSLPF,
    -- 慢性器官功能不全或免疫功能抑制（manXing 任一脏器标识为真则1）
    CASE WHEN COALESCE(s.a #>> '{manXing,isGan}','false')    = 'true'
           OR COALESCE(s.a #>> '{manXing,isShen}','false')  = 'true'
           OR COALESCE(s.a #>> '{manXing,isXin}','false')   = 'true'
           OR COALESCE(s.a #>> '{manXing,isMianyi}','false') = 'true'
           OR COALESCE(s.a #>> '{manXing,isHuxi}','false')  = 'true'
         THEN 1 ELSE 0 END                      AS BMXQGGNBQHMYGNYZ,
    -- 患者手术类型（calDead: isNoOper/isJiZhenShoushu）
    CASE
        WHEN (s.a #>> '{calDead,isNoOper}') = 'true'        THEN '非手术后'
        WHEN (s.a #>> '{calDead,isJiZhenShoushu}') = 'true' THEN '急诊手术后'
        WHEN s.a #>> '{calDead,score}' IS NOT NULL          THEN '择期手术后'
        ELSE NULL
    END                                         AS CHZSSLX,
    -- 慢性健康评分
    CAST(s.a #>> '{manXing,score}' AS smallint) AS IMXJKPF,
    -- 总分（年龄+急性生理+慢性健康）
    CAST(CAST(s.a #>> '{totalScore}' AS numeric) AS int)    AS IZF,
    -- 预期病死率（calDead.score，0-1比例小数）
    CAST(s.a #>> '{calDead,score}' AS numeric)  AS NYQBSL,
    -- 评估科室代码 / 名称
    p.deptcode                                  AS CPGKSDM,
    p.dept                                      AS CPGKSMC,
    -- 记录人代码 / 姓名
    s.inputuserid                               AS CJLRDM,
    s.inputuser                                 AS CJLRXM,
    -- 记录日期时间（评分时间）
    s.time                                      AS DJLRQSJ,
    -- 数据创建时间（评分时间，JSON内毫秒时间戳）
    CASE
        WHEN s.a #>> '{scoreDate}' IS NOT NULL
        THEN to_timestamp(CAST(s.a #>> '{scoreDate}' AS bigint) / 1000.0)
        ELSE NULL
    END                                         AS DSJCJSJ,
    -- 数据增量时间（同步时间，同 TB_ICU 先例）
    s.mongotokingdate                           AS DSJZLSJ,
    -- 数据状态（0-无效；1-有效；NULL视为有效）
    CASE WHEN s.valid IS FALSE THEN 0 ELSE 1 END AS BSJZT

FROM (
    SELECT
        *,
        NULLIF(apacheii, '')::jsonb AS a   -- apacheii 嵌套明细（JSON字符串/JSONB统一转jsonb）
    FROM score
    WHERE scoretype = 'apacheII'           -- 本视图仅针对APACHE II评分记录
      AND "valid" IS NOT FALSE              -- 仅保留有效记录（兼容 true 与 NULL，排除 false 失效记录）
) s
LEFT JOIN patient p
    ON s.pid = p.mongo_id;                 -- score.pid = patient._id(Mongo) → 同步后为 patient.mongo_id
