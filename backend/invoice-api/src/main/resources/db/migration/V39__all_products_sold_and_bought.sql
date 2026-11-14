-- Every product can be sold and bought; the per-product "Sales item" / "Purchase item" switches are gone from the UI.
UPDATE product SET is_sales_item = TRUE, is_purchase_item = TRUE WHERE NOT is_sales_item OR NOT is_purchase_item;
