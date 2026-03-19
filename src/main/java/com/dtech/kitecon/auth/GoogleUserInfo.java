package com.dtech.kitecon.auth;

import lombok.Builder;
import lombok.Data;

/**
 * User information extracted from Google ID token
 */
@Data
@Builder
public class GoogleUserInfo {
    private String googleId;
    private String email;
    private String name;
    private String givenName;
    private String familyName;
    private String pictureUrl;
    private boolean emailVerified;
}
