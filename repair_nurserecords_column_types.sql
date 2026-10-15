-- ============================================================================
-- 修复 ensureColumnsExist 列类型误判导致的写不进去问题
-- ============================================================================
-- 背景
--   KingbaseWriterService.ensureColumnsExist 旧实现按列名「猜」类型：
--       if (col.contains("count") || col.contains("times")) colType = "INTEGER";
--   而 "accountId" 里含子串 "count"（ac-count-id），"cancelAccountName" 同理，
--   于是这 3 个列被建成 INTEGER。写入 ObjectId（如 6a1f9f01e467916d244bbda8）
--   或中文姓名（如 陈芬）时 PostgreSQL 报：
--       ERROR: invalid input syntax for type integer: "6a1f9f01e467916d244bbda8"
--   结果 nurserecords 每轮同步有 1826 条记录写不进去（表现为该表长期缺数据）。
--
-- 影响范围（全库扫描确认，仅这 2 张表 6 个列）
--   nurserecords        : accountid / cancelaccountid / cancelaccountname
--   nurserecords_copy1  : accountid / cancelaccountid / cancelaccountname
--
-- 安全评估（已实测）
--   * 6 个列的「非空行数」均为 0 —— 即这些列从建出来那天起就没成功写入过任何值；
--   * integer -> text 是元数据变更，0 行数据需要转换，无数据丢失风险；
--   * 已在事务内演练：ALTER 后写 ObjectId / 中文姓名成功，ROLLBACK 后类型与数据均复原。
--
-- 执行目标库
--   生产 10.35.4.11:54321/SmartCare
--   测试 10.35.4.12:54321/SmartCare（如同样存在该问题，同样可执行）
-- ============================================================================

BEGIN;

ALTER TABLE nurserecords       ALTER COLUMN accountid         TYPE text USING accountid::text;
ALTER TABLE nurserecords       ALTER COLUMN cancelaccountid   TYPE text USING cancelaccountid::text;
ALTER TABLE nurserecords       ALTER COLUMN cancelaccountname TYPE text USING cancelaccountname::text;

ALTER TABLE nurserecords_copy1 ALTER COLUMN accountid         TYPE text USING accountid::text;
ALTER TABLE nurserecords_copy1 ALTER COLUMN cancelaccountid   TYPE text USING cancelaccountid::text;
ALTER TABLE nurserecords_copy1 ALTER COLUMN cancelaccountname TYPE text USING cancelaccountname::text;

COMMIT;

-- ----------------------------------------------------------------------------
-- 执行后自查（应全部返回 text）
-- ----------------------------------------------------------------------------
-- SELECT table_name, column_name, data_type
-- FROM information_schema.columns
-- WHERE table_schema = 'public'
--   AND table_name IN ('nurserecords', 'nurserecords_copy1')
--   AND column_name IN ('accountid', 'cancelaccountid', 'cancelaccountname')
-- ORDER BY table_name, column_name;
--
-- 预期结果：6 行，data_type 全部为 text
-- ============================================================================
