-- ============================================================
-- trade_order: add underlying price + stop/target columns
-- Auto-applied by Hibernate ddl-auto=update.
-- This file is a reference for manual review / MySQL Workbench.
-- ============================================================

ALTER TABLE trade_order
    ADD COLUMN underlying_entry_price DECIMAL(14,4) NULL AFTER realised_pnl,
    ADD COLUMN underlying_exit_price  DECIMAL(14,4) NULL AFTER underlying_entry_price,
    ADD COLUMN stop_loss              DECIMAL(14,4) NULL AFTER underlying_exit_price,
    ADD COLUMN target                 DECIMAL(14,4) NULL AFTER stop_loss;
