# Access Token authentication boundary

Spring Security OAuth2 Resource Server with Nimbus owns Access Token JWT signature, algorithm, and expiry validation. The system continues to issue the access-token JWT. To preserve the controller and authorization contracts, the converter maps a validated JWT to the existing `User` principal, Refresh Session UUID details, and `ROLE_` authorities. Every protected request checks Refresh Session and User status and access-token versions before authorization.
