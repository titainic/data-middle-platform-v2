-- Restore the data-classification category table used by the existing DG tree.
-- DG_DATA_CATEGORY currently has no rows or category-code references.
-- The established standard-data-element category has the same audited category schema.
CREATE TABLE IF NOT EXISTS DG_DATA_CATEGORY_CAT LIKE ATT_DATA_ELEM_CAT;
