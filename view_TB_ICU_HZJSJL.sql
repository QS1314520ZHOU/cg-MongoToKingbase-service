-- =====================================================
-- ICU患者接收记录视图 (TB_ICU_HZJSJL)
-- 数据来源：Kingbase patient表
-- 生成时间：2026-08-24
-- =====================================================

CREATE OR REPLACE VIEW TB_ICU_HZJSJL AS
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

    -- 紧急电话号码
    tel                                     AS CJJDHHM,

    -- 住院号
    mrn                                     AS CZYH,

    -- 病床号
    hisbed                                  AS CBCH,

    -- 原属病区代码
    admissionsourcecode                     AS CYSBQDM,

    -- 原属病区名称
    admissionsource                         AS CYSBQMC,

    -- 入科日期时间
    icuadmissiontime                        AS DRKRQSJ,

    -- 入科方式代码（转入->2, 入院->1, 急诊->1, 手术->3）
    CASE
        WHEN admissiontype = '转入' THEN '2'
        WHEN admissiontype = '入院' THEN '1'
        WHEN admissiontype = '急诊' THEN '1'
        WHEN admissiontype = '手术' THEN '3'
        ELSE '9'
    END                                     AS CRKFSDM,

    -- 入科方式名称
    admissiontype                           AS CRKFSMC,

    -- 非计划转入ICU（计划转入->false, 其他->true）
    CASE
        WHEN admissionplan = '计划转入' THEN FALSE
        WHEN admissionplan IS NOT NULL THEN TRUE
        ELSE NULL
    END                                     AS BFJHZRICU,

    -- 预期死亡（暂无对应字段）
    NULL                                    AS BYQSW,

    -- ICU病区代码
    deptcode                                AS CICUBQDM,

    -- ICU病区名称
    dept                                    AS CICUBQMC,

    -- ICU类型代码（暂无对应字段）
    NULL                                    AS CICULXDM,

    -- 非预期再入科（暂无对应字段）
    NULL                                    AS IFYQZRK,

    -- 主诊医师代码
    beddoctorid                             AS CZZHYSDM,

    -- 主诊医师签名
    bedphysician                            AS CZZHYSQM,

    -- 疾病诊断编码（从JSON数组提取第一个）
    CASE
        WHEN clinicaldiagnosiscodelist IS NOT NULL
             AND clinicaldiagnosiscodelist != '[]'
        THEN SPLIT_PART(
            REPLACE(REPLACE(REPLACE(clinicaldiagnosiscodelist, '[', ''), ']', ''), '"', ''),
            ',', 1)
        ELSE NULL
    END                                     AS CJBZDBM,

    -- 疾病诊断名称
    clinicaldiagnosis                       AS CJBZDMC,

    -- 手术/操作代码（暂无对应字段）
    NULL                                    AS CSSCZDM,

    -- 手术/操作名称（暂无对应字段）
    NULL                                    AS CSSCZMC,

    -- 备注
    chiefcomplaint                          AS CBZ,

    -- 数据创建时间
    createdtime                             AS DSJCJSJ,

    -- 数据增量时间
    mongotokingdate                         AS DSJZLSJ,

    -- 数据状态（true=有效）
    TRUE                                    AS BSJZT

FROM patient
WHERE status = 'admitted'        -- 只查询在科患者
  AND status <> 'invalid';       -- 排除已标记无效的记录（patient 表无 valid 字段，等价于 valid=false 过滤）
