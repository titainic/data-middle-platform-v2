/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without separate commercial license from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.common.utils.ca;

import cn.hutool.crypto.SecureUtil;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.security.auth.x500.X500Principal;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Generate a self-signed CA root certificate and save the private key as a PEM file
 * @author qdata
 */
public class CaGenerateRootCertificate {

    /**
     * Generate subject root certificate based on certificate information
     * @param dnNameStr certificate information
     * @return List<MultipartFile> The first one is the certificate, the second one is the private key
     */
    public static List<MultipartFile> generateRootCertificate(String dnNameStr) {
        List<MultipartFile> files = new ArrayList<>();
        try {
            // Generate RSA key pair
            KeyPair keyPair = SecureUtil.generateKeyPair("RSA", 2048);

            // Set the validity period of the certificate
            long currentTime = System.currentTimeMillis();
            Date startDate = new Date(currentTime);
            // Valid for 30 years
            Date endDate = new Date(currentTime + 365L * 30L * 24L * 60L * 60L * 1000L);

            // Set certificate information: self-signed, so issuer == subject
            X500Principal dnName = new X500Principal(dnNameStr);
            // Use current time as sequence number
            BigInteger certSerialNumber = new BigInteger(Long.toString(currentTime));

            // Create an X.509 v3 certificate and sign it with SHA256withRSA
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    dnName, certSerialNumber, startDate, endDate, dnName, keyPair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .setProvider(new BouncyCastleProvider())
                    .getCertificate(builder.build(signer));

            // Save the certificate as a.cer file
            String certFilePath = "rootCA.cer";
            ByteArrayOutputStream certOutputStream = new ByteArrayOutputStream();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(certOutputStream, StandardCharsets.US_ASCII))) {
                writer.write("-----BEGIN CERTIFICATE-----\n");
                writer.write(Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(certificate.getEncoded()));
                writer.write("\n-----END CERTIFICATE-----\n");
            }
            Files.write(Paths.get(certFilePath), certOutputStream.toByteArray());

            // Save private key as PEM file
            String privateKeyFilePath = "privateKey.pem";
            ByteArrayOutputStream pemOutputStream = new ByteArrayOutputStream();
            try (PemWriter pemWriter = new PemWriter(new OutputStreamWriter(pemOutputStream, StandardCharsets.US_ASCII))) {
                PemObject pemObject = new PemObject("PRIVATE KEY", keyPair.getPrivate().getEncoded());
                pemWriter.writeObject(pemObject);
            }

            // Convert the generated file to MultipartFile
            files.add(convertFileToMultipartFile(certFilePath, "rootCA.cer"));
            files.add(new MockMultipartFile(privateKeyFilePath, privateKeyFilePath, "application/x-pem-file", pemOutputStream.toByteArray()));

            // Delete original files
            deleteFile(certFilePath);
            deleteFile(privateKeyFilePath);

            System.out.println("Generated the root certificate and private key, converted them to MultipartFile objects, and deleted the original files");

        } catch (Exception e) {
            e.printStackTrace();
        }

        return files;
    }

    private static MultipartFile convertFileToMultipartFile(String filePath, String fileName) throws Exception {
        File file = new File(filePath);
        try (FileInputStream input = new FileInputStream(file)) {
            return new MockMultipartFile(fileName, fileName, "application/x-x509-ca-cert", input);
        }
    }

    private static void deleteFile(String filePath) {
        try {
            Files.delete(Paths.get(filePath));
            System.out.println("File deleted: " + filePath);
        } catch (Exception e) {
            System.out.println("Failed to delete file: " + filePath);
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Test generated self-signed certificate
        String dnNameStr = "CN=YcgtRootCA, OU=IT, O=盐城市国有资产投资集团有限公司, L=Yancheng, ST=Yancheng, C=CN";
        List<MultipartFile> files = generateRootCertificate(dnNameStr);

        // Print the generated file name
        for (MultipartFile file : files) {
            System.out.println("Generated file: " + file.getOriginalFilename());
        }
    }
}
