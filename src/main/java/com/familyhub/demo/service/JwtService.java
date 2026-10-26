package com.familyhub.demo.service;

import com.familyhub.demo.config.JwtConfig;
import com.familyhub.demo.model.Family;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.Key;
import java.util.Date;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class JwtService {
    /** The family's password version when the token was issued; see {@link Family#getPasswordVersion()}. */
    static final String PASSWORD_VERSION_CLAIM = "pwv";

    private final JwtConfig jwtConfig;


    public String generateToken(Family family) {
        return Jwts.builder()
                .subject(family.getId().toString())
                .claim(PASSWORD_VERSION_CLAIM, family.getPasswordVersion())
                .issuer(jwtConfig.getIssuer())
                .signWith(getSignInKey())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtConfig.getExpiration()))
                .compact();
    }

    /**
     * The password version the token was issued under; 0 for tokens issued before
     * versions existed.
     */
    public int extractPasswordVersion(String token) {
        Integer version = extractClaim(token, claims -> claims.get(PASSWORD_VERSION_CLAIM, Integer.class));
        return version == null ? 0 : version;
    }

    public String extractSubject(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = Jwts.parser()
                .verifyWith((SecretKey) getSignInKey())
                .requireIssuer(jwtConfig.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claimsResolver.apply(claims);
    }

    private Key getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtConfig.getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
