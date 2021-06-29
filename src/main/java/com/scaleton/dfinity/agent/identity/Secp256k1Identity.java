package com.scaleton.dfinity.agent.identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.Security;
import java.security.SignatureException;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.scaleton.dfinity.types.Principal;

public final class Secp256k1Identity implements Identity {
	static final Logger LOG = LoggerFactory.getLogger(Secp256k1Identity.class);

	static JcaPEMKeyConverter jcaPemKeyConverter = new JcaPEMKeyConverter();

	KeyPair keyPair;
	public byte[] derEncodedPublickey;

	static {
		Security.addProvider(new BouncyCastleProvider());
		jcaPemKeyConverter.setProvider(BouncyCastleProvider.PROVIDER_NAME);
	}

	Secp256k1Identity(KeyPair keyPair, byte[] derEncodedPublickey) {
		this.keyPair = keyPair;
		this.derEncodedPublickey = derEncodedPublickey;
	}

	public static Secp256k1Identity fromPEMFile(Path path) {
		try {

			PEMParser pemParser = new PEMParser(Files.newBufferedReader(path));

			Object pemObject = pemParser.readObject();

			if (pemObject instanceof PEMKeyPair) {
				KeyPair keyPair = jcaPemKeyConverter.getKeyPair((PEMKeyPair) pemObject);

				PublicKey publicKey = keyPair.getPublic();

				return new Secp256k1Identity(keyPair, publicKey.getEncoded());
			} else
				throw PemError.create(PemError.PemErrorCode.PEM_ERROR);

		} catch (IOException e) {
			throw PemError.create(PemError.PemErrorCode.PEM_ERROR, e);
		}
	}

	/// Create a Secp256k1Identity from a KeyPair
	public static Secp256k1Identity fromKeyPair(KeyPair keyPair) {
		PublicKey publicKey = keyPair.getPublic();

		return new Secp256k1Identity(keyPair, publicKey.getEncoded());
	}

	@Override
	public Principal sender() {
		return Principal.selfAuthenticating(derEncodedPublickey);
	}

	@Override
	public Signature sign(byte[] msg) {
		try {

			// Generate new signature
			java.security.Signature dsa = java.security.Signature.getInstance("SHA256withPLAIN-ECDSA", "BC");
			// ECDSA digital signature algorithm
			dsa.initSign(this.keyPair.getPrivate());

			dsa.update(msg);

			byte[] signature = dsa.sign();

			return new Signature(this.derEncodedPublickey, signature);

		} catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException | SignatureException e) {
			throw PemError.create(PemError.PemErrorCode.ERROR_STACK, e);
		}

	}

}
