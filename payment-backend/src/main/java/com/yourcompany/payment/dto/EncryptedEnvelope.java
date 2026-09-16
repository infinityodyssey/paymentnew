package com.yourcompany.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Application-layer encryption envelope, carried inside TLS.
 *
 * The AES-GCM additional authenticated data is the session id, which binds a
 * ciphertext to the session that produced it: a captured envelope cannot be replayed
 * into a different session even if the key were somehow shared.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncryptedEnvelope {

    @NotBlank
    @Pattern(regexp = "^[0-9a-fA-F]{24}$", message = "ivHex must be 12 bytes of hex")
    private String ivHex;

    @NotBlank
    @Pattern(regexp = "^[0-9a-fA-F]{2,}$", message = "ciphertextHex must be hex")
    private String ciphertextHex;

    @NotBlank
    @Pattern(regexp = "^[0-9a-fA-F]{32}$", message = "authTagHex must be 16 bytes of hex")
    private String authTagHex;
}
