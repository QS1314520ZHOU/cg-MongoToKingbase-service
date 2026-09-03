-- =====================================================
-- ICU患者生命体征视图 (TB_ICU_HZTZ)
-- 数据来源：Kingbase bedside 表（行转列） + patient 表
-- 生成时间：2026-09-03
-- 说明：
--   1. bedside 为行式体征存储：每个 (pid, time) 下每项的体征是一条独立记录，
--      本视图通过 CASE + GROUP BY(pid, time) 做 PIVOT 行转列。
--   2. bedside.pid 关联 patient.mongo_id 取患者基本信息。
--   3. 体征数值取自 bedside.strval（文本型，保持原始录入值）。
--   4. 注意：param_脉搏 在 Kingbase 中仅 1 条记录（基本为空），
--      实际脉搏数据在 param_PR（Pulse Rate）。如需脉搏取真实值，
--      可将 FMB 的 CASE 改为 b.code='param_PR'。
-- =====================================================

CREATE OR REPLACE VIEW TB_ICU_HZTZ AS
SELECT
    -- 医疗机构代码
    'H50010400021'                          AS CYLJGDM,

    -- 医疗机构名称
    '重钢总医院'                            AS CYLJGMC,

    -- 患者标识（hisID）
    p.hispid                                AS CHZBS,

    -- 患者姓名
    p.name                                  AS CZHXM,

    -- 住院号
    p.mrn                                   AS CZYH,

    -- 患者ID（bedside.pid，用于溯源）
    b.pid                                   AS CPID,

    -- 测量时间（bedside.time）
    b."time"                               AS DCSSJ,

    -- 体温 (param_T)
    MAX(CASE WHEN b.code = 'param_T'       THEN b.strval END)  AS FTW,

    -- 脉搏 (param_脉搏，注：该 code 在库中仅 1 条，建议改用 param_PR)
    MAX(CASE WHEN b.code = 'param_脉搏'    THEN b.strval END)  AS FMB,

    -- 收缩压 (param_nibp_s)
    MAX(CASE WHEN b.code = 'param_nibp_s'  THEN b.strval END)  AS FNXSSY,

    -- 舒张压 (param_nibp_d)
    MAX(CASE WHEN b.code = 'param_nibp_d'  THEN b.strval END)  AS FNXSZY,

    -- 血氧饱和度 (param_spo2)
    MAX(CASE WHEN b.code = 'param_spo2'    THEN b.strval END)  AS FXYBHD,

    -- 呼吸 (param_resp)
    MAX(CASE WHEN b.code = 'param_resp'    THEN b.strval END)  AS FHX,

    -- 心率 (param_HR)
    MAX(CASE WHEN b.code = 'param_HR'      THEN b.strval END)  AS FXL

FROM bedside b
LEFT JOIN patient p ON b.pid = p.mongo_id
WHERE b.code IN (
    'param_T',
    'param_脉搏',
    'param_nibp_s',
    'param_nibp_d',
    'param_spo2',
    'param_resp',
    'param_HR'
)
  -- 仅保留有效体征记录（valid 不等于 false：保留 true 与 NULL，排除 false）
  AND b.valid IS NOT FALSE
GROUP BY b.pid, b."time", p.hispid, p.name, p.mrn
ORDER BY b.pid, b."time";
