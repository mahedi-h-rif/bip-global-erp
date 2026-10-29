package com.fatoora.api.egs;

import com.fatoora.zatca.api.FatooraClient;
import com.fatoora.zatca.crypto.ZatcaCertificate;

import java.security.PrivateKey;

/** Decrypted production credentials of an EGS unit, held only for the duration of one operation. */
public record SigningMaterial(ZatcaCertificate certificate, PrivateKey privateKey, FatooraClient.Credentials credentials) {}
