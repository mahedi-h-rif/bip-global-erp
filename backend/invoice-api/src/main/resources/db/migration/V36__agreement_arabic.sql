-- Arabic version of the agreement template and of each agreement (the PDF can be printed in either language).
ALTER TABLE agreement_template ADD COLUMN title_ar varchar(255);
ALTER TABLE agreement_template ADD COLUMN body_ar text;
ALTER TABLE agreement ADD COLUMN title_ar varchar(255);
ALTER TABLE agreement ADD COLUMN body_ar text;
