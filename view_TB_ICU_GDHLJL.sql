-- =====================================================
-- ICU管道护理记录视图 (TB_ICU_GDHLJL)
-- 数据来源：Kingbase tubeexe表 + patient表
-- 生成时间：2026-08-24
-- 说明：展开tubeRecordList数组，每条护理记录一行
-- =====================================================

CREATE OR REPLACE VIEW TB_ICU_GDHLJL AS
SELECT
    -- 医疗机构代码
    'H50010400021'                          AS CYLJGDM,

    -- 医疗机构名称
    '重钢总医院'                            AS CYLJGMC,

    -- 记录ID（管道ID + 护理记录时间戳组合唯一标识）
    t.mongo_id || '_' || (rec->>'time')     AS CJLID,

    -- 住院号
    p.mrn                                   AS CZYH,

    -- 入科次数
    CAST(p.hospitaltime AS SMALLINT)        AS IRKCS,

    -- 护理日期时间（将毫秒时间戳转换为timestamp）
    TO_TIMESTAMP(
        CAST(rec->>'time' AS BIGINT) / 1000
    )                                       AS DHLRQSJ,

    -- 管道分类编码
    t.type                                  AS CGDFLBM,

    -- 管道分类名称
    t.name                                  AS CGDFLMC,

    -- 管道-引流管名称（引流管类型时填充）
    CASE
        WHEN t.type IN ('引流管', 'T管', '胸腔引流管', '腹腔引流管')
        THEN t.name
        ELSE NULL
    END                                     AS CGD_YLGMC,

    -- 管道-引流管挤压（暂无对应字段）
    NULL                                    AS CGD_YLGJY,

    -- 管道-引流管更换（从catheterNurse中判断）
    CASE
        WHEN rec->>'catheterNurse' LIKE '%更换引流袋%'
        THEN '是'
        ELSE NULL
    END                                     AS CGD_YLGGH,

    -- 管道-静脉颜色（静脉类型时从color字段获取）
    CASE
        WHEN t.type IN ('中心静脉', '外周静脉', 'PICC')
        THEN rec->>'color'
        ELSE NULL
    END                                     AS CGD_JMYS,

    -- 管道-静脉名称（静脉类型时填充）
    CASE
        WHEN t.type IN ('中心静脉', '外周静脉', 'PICC')
        THEN t.name
        ELSE NULL
    END                                     AS CGD_JMMC,

    -- 管道-静脉护理（从catheterNurse中提取）
    CASE
        WHEN t.type IN ('中心静脉', '外周静脉', 'PICC')
        THEN rec->>'catheterNurse'
        ELSE NULL
    END                                     AS CGD_JMHL,

    -- 非计划拔管
    COALESCE(t.unplannedendtube, FALSE)     AS BFJHBG,

    -- 是否管道滑脱（从tubedrawingreason判断）
    CASE
        WHEN t.tubedrawingreason LIKE '%滑脱%'
        THEN TRUE
        ELSE FALSE
    END                                     AS BGDHT,

    -- 管道滑脱再插管（暂无对应字段）
    NULL                                    AS BGDHTZCG,

    -- 是否48小时内再置管
    COALESCE(t.nocalagain48h, FALSE)        AS B48XSNZZG,

    -- 护士编码（暂无对应字段，使用recordUser）
    rec->>'recordUser'                      AS CHSBM,

    -- 护士签名
    rec->>'recordUserName'                  AS CHSQM,

    -- 数据创建时间
    t.mongotokingdate                       AS DSJCJSJ,

    -- 数据增量时间
    t.mongotokingdate                       AS DSJZLSJ,

    -- 数据状态（true=有效）
    CASE
        WHEN rec->>'valid' = 'true' THEN TRUE
        ELSE FALSE
    END                                     AS BSJZT

FROM tubeexe t
    INNER JOIN patient p ON t.pid = p.mongo_id,
    LATERAL jsonb_array_elements(t.tuberecordlist) AS rec
WHERE t.valid = TRUE                        -- 只查询有效的管道记录
  AND p.status = 'admitted'                 -- 只查询在科患者
  AND rec->>'valid' = 'true';               -- 只查询有效的护理记录
