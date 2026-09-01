package com.fherrmann.food.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Umbau der Signatur von DER nach JOSE.
 *
 * <p>Das ist die eine Stelle in der Push-Anbindung, an der ein Fehler nicht
 * als Fehler auffaellt: Apple antwortet mit {@code InvalidProviderToken}, und
 * das sieht aus wie ein falscher Schluessel oder eine falsche Team-ID. Wer
 * dort sucht, findet nichts.
 */
class ApnsClientTest {

    @Test
    @DisplayName("Eine echte Signatur wird immer zu genau 64 Byte")
    void realSignaturesBecomeSixtyFourBytes() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keys = generator.generateKeyPair();

        // Mehrfach, weil die DER-Laenge je nach Zufallswerten schwankt: mal
        // 70, mal 71, mal 72 Byte. Genau daran scheitert eine Umrechnung, die
        // feste Offsets annimmt.
        for (int i = 0; i < 50; i++) {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keys.getPrivate());
            signature.update(("nachricht-" + i).getBytes());
            assertThat(ApnsClient.toJose(signature.sign())).hasSize(64);
        }
    }

    @Test
    @DisplayName("Ein kurzes R wird rechtsbuendig eingesetzt, nicht linksbuendig")
    void shortValuesAreRightAligned() {
        // R ist nur 31 Byte lang (fuehrende Null im Wert selbst), S volle 32.
        byte[] r = new byte[31];
        Arrays.fill(r, (byte) 0x11);
        byte[] s = new byte[32];
        Arrays.fill(s, (byte) 0x22);

        byte[] jose = ApnsClient.toJose(der(r, s));

        assertThat(jose[0]).isZero();
        assertThat(jose[1]).isEqualTo((byte) 0x11);
        assertThat(jose[31]).isEqualTo((byte) 0x11);
        assertThat(jose[32]).isEqualTo((byte) 0x22);
        assertThat(jose[63]).isEqualTo((byte) 0x22);
    }

    @Test
    @DisplayName("Die fuehrende Null aus DER faellt weg")
    void leadingPaddingByteIsDropped() {
        // DER haengt eine 0x00 davor, wenn das oberste Bit gesetzt ist -
        // sonst waere die Zahl negativ. Die gehoert nicht in die 32 Byte.
        byte[] r = new byte[33];
        r[0] = 0x00;
        Arrays.fill(r, 1, 33, (byte) 0xAA);
        byte[] s = new byte[32];
        Arrays.fill(s, (byte) 0xBB);

        byte[] jose = ApnsClient.toJose(der(r, s));

        assertThat(jose[0]).isEqualTo((byte) 0xAA);
        assertThat(jose[31]).isEqualTo((byte) 0xAA);
        assertThat(jose[32]).isEqualTo((byte) 0xBB);
    }

    /** Baut die DER-Struktur, die die JCA liefert: SEQUENCE aus zwei INTEGERn. */
    private static byte[] der(byte[] r, byte[] s) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x02);
        body.write(r.length);
        body.writeBytes(r);
        body.write(0x02);
        body.write(s.length);
        body.writeBytes(s);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.write(body.size());
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }
}
