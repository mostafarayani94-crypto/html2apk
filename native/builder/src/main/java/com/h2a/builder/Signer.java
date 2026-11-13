package com.h2a.builder;

import android.content.Context;
import com.android.apksig.ApkSigner;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.Date;

/** مفتاح توقيع واحد للتطبيقات كلها (بيتولّد أول مرة ويتحفظ جوه التطبيق). نفس المفتاح = التحديثات بتتثبّت فوق القديم. */
final class Signer {
    private final File keyFile, certFile;

    Signer(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "signing");
        dir.mkdirs();
        keyFile = new File(dir, "key.pk8");
        certFile = new File(dir, "cert.der");
    }

    boolean hasKey() { return keyFile.exists() && certFile.exists(); }

    private void ensureKey() throws Exception {
        if (hasKey()) return;
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X500Name name = new X500Name("CN=H2A App, O=H2A");
        Date from = new Date(System.currentTimeMillis() - 86400000L);
        Date to = new Date(from.getTime() + 30L * 365 * 86400000L);
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.currentTimeMillis()), from, to, name, kp.getPublic());
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(cs));
        write(keyFile, kp.getPrivate().getEncoded());
        write(certFile, cert.getEncoded());
    }

    byte[] exportKey() throws Exception { ensureKey(); return read(keyFile); }
    byte[] exportCert() throws Exception { ensureKey(); return read(certFile); }

    /** استيراد مفتاح من نسخة احتياطية (بيتحقق منه قبل ما يحفظه). */
    void importKey(byte[] pk8, byte[] certDer) throws Exception {
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pk8));
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certDer));
        write(keyFile, pk8);
        write(certFile, certDer);
    }

    void sign(File in, File out) throws Exception {
        ensureKey();
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(keyFile)));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(read(certFile)));
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("h2a", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
            .setInputApk(in).setOutputApk(out).setMinSdkVersion(24)
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
            .build().sign();
    }

    private static byte[] read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return b;
        } finally { in.close(); }
    }

    private static void write(File f, byte[] b) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(b); } finally { o.close(); }
    }
}
