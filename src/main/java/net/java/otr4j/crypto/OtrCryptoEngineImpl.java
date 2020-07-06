/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.java.otr4j.crypto;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.DSAPublicKey;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import net.java.otr4j.io.SerializationUtils;

/**
 * 
 * @author George Politis
 */
public class OtrCryptoEngineImpl implements OtrCryptoEngine {

	private static final String KEY_PAIR_GENERATOR_ALGORITHM_DH = "DH";

	private static final String CIPHER_ALGORITHM = "AES/CTR/NoPadding";
	private static final String CIPHER_NAME = "AES";

	private static final String DSA_SIGNATURE_ALGORITHM = "NONEwithDSAinP1363Format";

	/**
	 * DSA signing is used without first computing a digest of the data, so there is a prescribed length for such input
	 * data.
	 */
	private static final int DSA_RAW_DATA_LENGTH = 20;

	private static final int DSA_KEY_LENGTH = 1024;

	@Override
	public KeyPair generateDSAKeyPair() {
		try {
			final KeyPairGenerator kg = KeyPairGenerator.getInstance("DSA");
			kg.initialize(DSA_KEY_LENGTH);
			return kg.genKeyPair();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("DSA algorithm is not supported.", e);
		}
	}

	@Override
	public KeyPair generateDHKeyPair() {
		try {
			KeyPairGenerator gen = KeyPairGenerator.getInstance(KEY_PAIR_GENERATOR_ALGORITHM_DH);
			gen.initialize(new DHParameterSpec(MODULUS, GENERATOR, DH_PRIVATE_KEY_MINIMUM_BIT_LENGTH));
			return gen.generateKeyPair();
		} catch (InvalidAlgorithmParameterException e) {
			throw new IllegalStateException("BUG: invalid algorithm parameter provided.", e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("BUG: DH algorithm is unavailable (for keypair generation).", e);
		}
	}

	public DHPublicKey getDHPublicKey(byte[] mpiBytes) throws OtrCryptoException {
		return getDHPublicKey(new BigInteger(mpiBytes));
	}

	@Override
	public DHPublicKey getDHPublicKey(BigInteger mpi) throws OtrCryptoException {
		DHPublicKeySpec pubKeySpecs = new DHPublicKeySpec(mpi, MODULUS, GENERATOR);
		try {
			KeyFactory keyFac = KeyFactory.getInstance("DH");
			return (DHPublicKey) keyFac.generatePublic(pubKeySpecs);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("DH algorithm is unavailable.", e);
		} catch (InvalidKeySpecException e) {
			throw new OtrCryptoException(e);
		}
	}

	@Override
	public byte[] sha256Hmac(byte[] b, byte[] key) throws OtrCryptoException {
		return this.sha256Hmac(b, key, 0);
	}

	@Override
	public byte[] sha256Hmac(byte[] b, byte[] key, int length) throws OtrCryptoException {
		SecretKeySpec keyspec = new SecretKeySpec(key, "HmacSHA256");
		javax.crypto.Mac mac;
		try {
			mac = javax.crypto.Mac.getInstance("HmacSHA256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("HmacSHA256 is unavailable.");
		}
		try {
			mac.init(keyspec);
		} catch (InvalidKeyException e) {
			throw new OtrCryptoException(e);
		}

		byte[] macBytes = mac.doFinal(b);

		if (length > 0) {
			byte[] bytes = new byte[length];
			ByteBuffer buff = ByteBuffer.wrap(macBytes);
			buff.get(bytes);
			return bytes;
		} else {
			return macBytes;
		}
	}

	@Override
	public byte[] sha1Hmac(byte[] b, byte[] key, int length) throws OtrCryptoException {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));

			byte[] macBytes = mac.doFinal(b);

			if (length > 0) {
				byte[] bytes = new byte[length];
				ByteBuffer buff = ByteBuffer.wrap(macBytes);
				buff.get(bytes);
				return bytes;
			} else {
				return macBytes;
			}
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("HmacSHA1 algorithm support is unavailable.", e);
		} catch (InvalidKeyException e) {
			throw new OtrCryptoException(e);
		}
	}

	@Override
	public byte[] sha256Hmac160(byte[] b, byte[] key) throws OtrCryptoException {
		return sha256Hmac(b, key, 20);
	}

	@Override
	public byte[] sha256Hash(byte[] b) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			sha256.update(b, 0, b.length);
			return sha256.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 support is unavailable.", e);
		}
	}

	@Override
	public byte[] sha1Hash(byte[] b) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-1");
			sha256.update(b, 0, b.length);
			return sha256.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 support is unavailable.", e);
		}
	}

	/**
	 * Decrypt AES-encrypted ciphertext.
	 *
	 * @param key the secret key
	 * @param ctr the counter value
	 * @param b   the ciphertext
	 * @return Returns the plaintext message.
	 * @throws OtrCryptoException Invalid or illegal key or counter provided.
	 */
	@Override
	public byte[] aesDecrypt(byte[] key, byte[] ctr, byte[] b) throws OtrCryptoException {
		try {
			return aesCipher(Cipher.DECRYPT_MODE, key, ctr).doFinal(b);
		} catch (IllegalBlockSizeException e) {
			throw new IllegalStateException("BUG: invalid block size specified.", e);
		} catch (BadPaddingException e) {
			throw new IllegalStateException("BUG: no padding is supposed to be used.", e);
		}
	}

	@Override
	public byte[] aesEncrypt(byte[] key, byte[] ctr, byte[] b) throws OtrCryptoException {
		try {
			return aesCipher(Cipher.ENCRYPT_MODE, key, ctr).doFinal(b);
		} catch (IllegalBlockSizeException e) {
			throw new IllegalStateException("BUG: invalid block size specified.", e);
		} catch (BadPaddingException e) {
			throw new IllegalStateException("BUG: no padding is supposed to be used.", e);
		}
	}

	private Cipher aesCipher(final int mode, final byte[] key, final byte[] ctr) throws OtrCryptoException {
		try {
			final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			cipher.init(mode, new SecretKeySpec(key, CIPHER_NAME),
					new IvParameterSpec(ctr == null ? new byte[AES_CTR_BYTE_LENGTH] : ctr));
			return cipher;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("BUG: AES cipher is not supported by Java run-time.", e);
		} catch (NoSuchPaddingException e) {
			throw new IllegalStateException("BUG: no padding is supposed to be used.", e);
		} catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
			throw new OtrCryptoException(e);
		}
	}

	@Override
	public BigInteger generateSecret(PrivateKey privKey, PublicKey pubKey) throws OtrCryptoException {
		try {
			KeyAgreement ka = KeyAgreement.getInstance("DH");
			ka.init(privKey);
			ka.doPhase(pubKey, true);
			byte[] sb = ka.generateSecret();
			return new BigInteger(1, sb);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("DH key agreement algorithm is unavailable.", e);
		} catch (InvalidKeyException e) {
			throw new OtrCryptoException(e);
		}
	}

	@Override
	public byte[] sign(byte[] b, PrivateKey privatekey) throws OtrCryptoException {
		if (!(privatekey instanceof DSAPrivateKey))
			throw new IllegalArgumentException("Illegal type of private key provided. Only DSA private keys are supported.");
		try {
			Signature signer = Signature.getInstance(DSA_SIGNATURE_ALGORITHM);
			signer.initSign(privatekey);
			final byte[] data = b.length == DSA_RAW_DATA_LENGTH ? b
					: bytesModQ(((DSAPrivateKey) privatekey).getParams().getQ(), b);
			signer.update(data);
			return signer.sign();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("DSA signature algorithm is not available.", e);
		} catch (InvalidKeyException | SignatureException e) {
			throw new OtrCryptoException(e);
		}
	}

	@Override
	public boolean verify(byte[] b, PublicKey pubKey, byte[] rs) throws OtrCryptoException {
		if (!(pubKey instanceof DSAPublicKey))
			throw new IllegalArgumentException("Illegal type of public key provided. Only DSA public keys are supported.");
		try {
			Signature signer = Signature.getInstance(DSA_SIGNATURE_ALGORITHM);
			signer.initVerify(pubKey);
			final byte[] data = b.length == DSA_RAW_DATA_LENGTH ? b
					: bytesModQ(((DSAPublicKey)pubKey).getParams().getQ(), b);
			signer.update(data);
			return signer.verify(rs);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("DSA signature algorithm is not available.", e);
		} catch (InvalidKeyException | SignatureException e) {
			throw new OtrCryptoException(e);
		}
	}

	private byte[] bytesModQ(final BigInteger q, final byte[] data) {
		return Util.asUnsignedByteArray(DSA_RAW_DATA_LENGTH, new BigInteger(1, data).mod(q));
	}

	@Override
	public String getFingerprint(PublicKey pubKey) throws OtrCryptoException {
		byte[] b = getFingerprintRaw(pubKey);
		return SerializationUtils.byteArrayToHexString(b);
	}

	@Override
	public byte[] getFingerprintRaw(PublicKey pubKey) throws OtrCryptoException {
		byte[] b;
		try {
			byte[] bRemotePubKey = SerializationUtils.writePublicKey(pubKey);

			if (pubKey.getAlgorithm().equals("DSA")) {
				byte[] trimmed = new byte[bRemotePubKey.length - 2];
				System.arraycopy(bRemotePubKey, 2, trimmed, 0, trimmed.length);
				b = sha1Hash(trimmed);
			} else {
				b = sha1Hash(bRemotePubKey);
			}
		} catch (IOException e) {
			throw new OtrCryptoException(e);
		}
		return b;
	}
}
