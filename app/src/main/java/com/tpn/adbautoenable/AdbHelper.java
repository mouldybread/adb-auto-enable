package com.tpn.adbautoenable;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.conscrypt.Conscrypt;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;

public class AdbHelper {
    private static final String TAG = "ADBAutoEnable";
    private Context context;
    private SimpleAdbManager adbManager;

    public AdbHelper(Context context) {
        this.context = context;
        try {
            // Install security providers
            Log.i(TAG, "Installing security providers");
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            Log.i(TAG, "Creating ADB manager");
            this.adbManager = SimpleAdbManager.getInstance(context);
            if (this.adbManager == null) {
                Log.e(TAG, "ADB manager is null after getInstance");
            } else {
                Log.i(TAG, "ADB manager created successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ADB helper", e);
            e.printStackTrace();
        }
    }

    public boolean pair(String host, int port, String code) {
        if (adbManager == null) {
            Log.e(TAG, "Cannot pair - ADB manager is null");
            return false;
        }

        try {
            Log.i(TAG, "Pairing with " + host + ":" + port + " using code: " + code);
            adbManager.pair(host, port, code);
            Log.i(TAG, "Pairing successful!");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Pairing failed", e);
            e.printStackTrace();
            return false;
        }
    }

    public boolean connect(String host, int port) {
        if (adbManager == null) {
            Log.e(TAG, "Cannot connect - ADB manager is null");
            return false;
        }

        try {
            adbManager.connect(host, port);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Connect failed", e);
            return false;
        }
    }

    public boolean switchToPort5555(String host, int port) {
        if (adbManager == null) {
            Log.e(TAG, "Cannot switch port - ADB manager is null");
            return false;
        }

        try {
            Log.i(TAG, "Connecting to " + host + ":" + port);
            adbManager.connect(host, port);

            Log.i(TAG, "Sending tcpip:5555 service command");
            AdbStream stream = adbManager.openStream("tcpip:5555");

            // Read response if any using InputStream
            InputStream inputStream = stream.openInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead = inputStream.read(buffer);
            if (bytesRead > 0) {
                String response = new String(buffer, 0, bytesRead);
                Log.i(TAG, "Response: " + response);
            }

            stream.close();
            adbManager.close();

            // Wait for ADB to restart on new port
            Thread.sleep(3000);

            Log.i(TAG, "Successfully switched to port 5555");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to switch to port 5555", e);
            e.printStackTrace();
            return false;
        }
    }

    private static class SimpleAdbManager extends AbsAdbConnectionManager {
        private static SimpleAdbManager INSTANCE;
        private PrivateKey privateKey;
        private PublicKey publicKey;
        private X509Certificate certificate;
        private Context context;
        private File keyFile;
        private File pubKeyFile;
        private File certFile;

        public static synchronized SimpleAdbManager getInstance(Context context) {
            if (INSTANCE == null) {
                try {
                    Log.i(TAG, "Creating new SimpleAdbManager instance");
                    INSTANCE = new SimpleAdbManager(context);
                    Log.i(TAG, "SimpleAdbManager instance created");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to create SimpleAdbManager", e);
                    e.printStackTrace();
                    return null;
                }
            }
            return INSTANCE;
        }

        private SimpleAdbManager(Context context) throws Exception {
            Log.i(TAG, "SimpleAdbManager constructor starting");
            this.context = context.getApplicationContext();
            setApi(Build.VERSION.SDK_INT);

            keyFile = new File(context.getFilesDir(), "adb_key");
            pubKeyFile = new File(context.getFilesDir(), "adb_key.pub");
            certFile = new File(context.getFilesDir(), "adb_cert");

            Log.i(TAG, "Key files: " + keyFile.getAbsolutePath());
            loadOrGenerateKeyPair();
            Log.i(TAG, "SimpleAdbManager initialized successfully");
        }

        private void loadOrGenerateKeyPair() throws Exception {
            Log.i(TAG, "Loading or generating key pair");
            if (keyFile.exists() && pubKeyFile.exists() && certFile.exists()) {
                Log.i(TAG, "Loading existing key pair and certificate");
                try {
                    // Load private key
                    byte[] privateKeyBytes = new byte[(int) keyFile.length()];
                    FileInputStream fis = new FileInputStream(keyFile);
                    fis.read(privateKeyBytes);
                    fis.close();

                    PKCS8EncodedKeySpec privateSpec = new PKCS8EncodedKeySpec(privateKeyBytes);
                    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                    privateKey = keyFactory.generatePrivate(privateSpec);
                    Log.i(TAG, "Private key loaded");

                    // Load public key
                    byte[] publicKeyBytes = new byte[(int) pubKeyFile.length()];
                    fis = new FileInputStream(pubKeyFile);
                    fis.read(publicKeyBytes);
                    fis.close();

                    X509EncodedKeySpec publicSpec = new X509EncodedKeySpec(publicKeyBytes);
                    publicKey = keyFactory.generatePublic(publicSpec);
                    Log.i(TAG, "Public key loaded");

                    // Load certificate
                    byte[] certBytes = new byte[(int) certFile.length()];
                    fis = new FileInputStream(certFile);
                    fis.read(certBytes);
                    fis.close();

                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
                    Log.i(TAG, "Certificate loaded");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load existing keys, generating new ones", e);
                    generateNewKeyPairAndCert();
                }
            } else {
                Log.i(TAG, "No existing keys found, generating new ones");
                generateNewKeyPairAndCert();
            }
        }

        private void generateNewKeyPairAndCert() throws Exception {
            Log.i(TAG, "Generating new RSA key pair");
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();
            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();
            Log.i(TAG, "Key pair generated");

            // Generate self-signed certificate
            Log.i(TAG, "Generating self-signed certificate");
            certificate = generateSelfSignedCertificate(keyPair);
            Log.i(TAG, "Certificate generated");

            // Save keys
            Log.i(TAG, "Saving keys to files");
            FileOutputStream fos = new FileOutputStream(keyFile);
            fos.write(privateKey.getEncoded());
            fos.close();

            fos = new FileOutputStream(pubKeyFile);
            fos.write(publicKey.getEncoded());
            fos.close();

            // Save certificate
            fos = new FileOutputStream(certFile);
            fos.write(certificate.getEncoded());
            fos.close();

            Log.i(TAG, "Keys and certificate saved successfully");
        }

        private X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
            X500Name issuer = new X500Name("CN=ADBAutoEnable");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L);
            Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

            SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());

            X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                    issuer,
                    serial,
                    notBefore,
                    notAfter,
                    issuer,
                    publicKeyInfo
            );

            // Don't specify provider - let it use default Android provider
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.getPrivate());

            X509CertificateHolder certHolder = certBuilder.build(signer);

            // FIXED: Removed .setProvider("BC") to use default Android provider
            return new JcaX509CertificateConverter()
                    .getCertificate(certHolder);
        }

        @Override
        protected PrivateKey getPrivateKey() {
            return privateKey;
        }

        @Override
        protected Certificate getCertificate() {
            return certificate;
        }

        @Override
        protected String getDeviceName() {
            return "ADBAutoEnable";
        }
    }
}
