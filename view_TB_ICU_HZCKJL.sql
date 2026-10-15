-- =====================================================
-- ICU患者出科记录视图 (TB_ICU_HZCKJL)
-- 数据来源：Kingbase patient表
-- 生成时间：2026-08-24
-- =====================================================

CREATE OR REPLACE VIEW TB_ICU_HZCKJL AS
SELECT
    -- 医疗机构代码
    'H50010400021'                          AS CYLJGDM,

    -- 医疗机构名称
    '重钢总医院'                            AS CYLJGMC,

    -- 记录ID（使用mongo_id）
    mongo_id                                AS CJLID,

    -- 入科次数
    CAST(hospitaltime AS SMALLINT)          AS IRKCS,

    -- 患者标识
    hispid                                  AS CHZBS,

    -- 患者姓名
    name                                    AS CHZXM,

    -- 性别代码（Male->1, Female->2）
    CASE
        WHEN gender = 'Male' THEN '1'
        WHEN gender = 'Female' THEN '2'
        ELSE NULL
    END                                     AS CXBDM,

    -- 性别名称
    CASE
        WHEN gender = 'Male' THEN '男'
        WHEN gender = 'Female' THEN '女'
        ELSE gender
    END                                     AS CXBMC,

    -- 出生日期
    birthday                                AS DCSRQ,

    -- 住院号
    mrn                                     AS CZYH,

    -- 病床号
    hisbed                                  AS CBCH,

    -- ICU病区代码
    deptcode                                AS CICUBQDM,

    -- ICU病区名称
    dept                                    AS CICUBQMC,

    -- 主诊医师代码
    beddoctorid                             AS CZZHYSDM,

    -- 主诊医师签名
    bedphysician                            AS CZZHYSQM,

    -- 入科时感染性休克标志（暂无对应字段）
    NULL                                    AS BRKSGRXXK,

    -- 3小时集束化治疗标志（暂无对应字段）
    NULL                                    AS B3XSJSHZL,

    -- 6小时集束化治疗标志（暂无对应字段）
    NULL                                    AS B6XSJSHZL,

    -- 出科日期时间
    icudischargetime                        AS DCKRQSJ,

    -- 患者去向代码
    dischargeddepartmentcode                AS CHZQXDM,

    -- 患者去向名称
    dischargeddepartment                    AS CHZQXMC,

    -- 备注
    chiefcomplaint                          AS CBZ,

    -- 数据创建时间
    createdtime                             AS DSJCJSJ,

    -- 数据增量时间
    mongotokingdate                         AS DSJZLSJ,

    -- 数据状态（true=有效）
    TRUE                                    AS BSJZT

FROM patient
WHERE status = 'discharged'  -- 只查询已出科患者
  AND icudischargetime IS NOT NULL
  AND status <> 'invalid';    -- 排除已标记无效的记录（patient 表无 valid 字段，等价于 valid=false 过滤）
