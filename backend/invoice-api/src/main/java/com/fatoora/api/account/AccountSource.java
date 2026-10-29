package com.fatoora.api.account;

/** Where a cash-book row came from; re-importing a month replaces only rows of the same import source. */
public enum AccountSource { MANUAL, IMPORT, IMPORT_YEAR }
