package com.virtru.keycloak;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.models.*;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Map;

/**
 * Custom OIDC Protocol Mapper that interfaces with an Attribute Provider
 * Endpoint to retrieve custom claims to be
 * placed in a configured custom claim name
 *
 * - Configurable properties allow for providing additional header and proprty
 * values to be passed to the attribute provider.
 *
 */
public class TdfClaimsMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    public static final String PROVIDER_ID = "virtru-oidc-protocolmapper";

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<ProviderConfigProperty>();

    final static String REMOTE_URL = "remote.url";
    final static String REMOTE_HEADERS = "remote.headers";
    final static String REMOTE_PARAMETERS = "remote.parameters";
    final static String REMOTE_PARAMETERS_USERNAME = "remote.parameters.username";
    final static String REMOTE_PARAMETERS_CLIENTID = "remote.parameters.clientid";
    final static String CLAIM_NAME = "claim.name";
    final static String PUBLIC_KEY_HEADER = "client.publickey";
    final static String CLAIM_REQUEST_TYPE = "claim_request_type";

    private CloseableHttpClient client = HttpClientBuilder.create().build();

    private Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Inner configuration to cache retrieved authorization for multiple tokens
     */
    private final static String REMOTE_AUTHORIZATION_ATTR = "remote-authorizations";

    static {
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, TdfClaimsMapper.class);
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        configProperties.get(configProperties.size() - 1).setDefaultValue("tdf_claims");

        configProperties.add(new ProviderConfigProperty(REMOTE_URL, "Attribute Provider URL",
                "Full URL of the remote attribute provider service endpoint. Overrides the \"CLAIMS_URL\" environment variable setting",
                ProviderConfigProperty.STRING_TYPE, null));

        configProperties.add(new ProviderConfigProperty(REMOTE_PARAMETERS, "Parameters",
                "List of additional parameters to send separated by '&'. Separate parameter name and value by an equals sign '=', the value can contain equals signs (ex: scope=all&full=true).",
                ProviderConfigProperty.STRING_TYPE, null));

        configProperties.add(new ProviderConfigProperty(REMOTE_HEADERS, "Headers",
                "List of headers to send separated by '&'. Separate header name and value by an equals sign '=', the value can contain equals signs (ex: Authorization=az89d).",
                ProviderConfigProperty.STRING_TYPE, null));

        configProperties.add(new ProviderConfigProperty(PUBLIC_KEY_HEADER, "Client Public Key Header Name",
                "Header name containing tdf client public key",
                ProviderConfigProperty.STRING_TYPE, "X-VirtruPubKey"));

    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getDisplayType() {
        return "OIDC to Entity Attribute Claim Mapper";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Provides Attribute Custom Claims";
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession keycloakSession,
            ClientSessionContext clientSessionCtx) {

        // FIXME We have to override the `sub` property so that it's the user's
        // name/email and not just the Keycloak UID - and the reason we have to
        // do this is because of how legacy code expects `dissems` to work.
        //
        // We will have to fix `dissems` to properly get rid of this hack.
        token.setSubject(userSession.getUser().getId());
        logger.info("Custom claims mapper triggered");

        String clientPK = getClientPublicKey(mappingModel, keycloakSession);
        JsonNode claims = clientSessionCtx.getAttribute(REMOTE_AUTHORIZATION_ATTR, JsonNode.class);
        // If no PK in request, don't bother asking for claims - no authorization.
        if (clientPK == null) {
            logger.info("No public key in auth request, skipping remote auth call and returning empty claims");
            claims = null;
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Fetch remote claims = " + (claims == null));
            }

            // If claims are not cached OR this is a userinfo request (which should always
            // refresh claims from remote) then refresh claims.
            if (claims == null || OIDCAttributeMapperHelper.includeInUserInfo(mappingModel)) {
                logger.debug("Getting remote authorizations");
                JsonNode entitlements = getRemoteAuthorizations(mappingModel, userSession, token);
                claims = buildClaimsObject(entitlements, clientPK);
                clientSessionCtx.setAttribute(REMOTE_AUTHORIZATION_ATTR, claims);
            } else {
                logger.debug("Looks like remote authorizations are already cached, not refreshing...");
                logger.debug("Cached claims are: " + claims);
            }
        }
        OIDCAttributeMapperHelper.mapClaim(token, mappingModel, claims);
    }

    private Map<String, Object> getHeaders(ProtocolMapperModel mappingModel, UserSessionModel userSession) {
        return buildMapFromStringConfig(mappingModel.getConfig().get(REMOTE_HEADERS));
    }

    private Map<String, Object> buildMapFromStringConfig(String config) {
        final Map<String, Object> map = new HashMap<>();

        // FIXME: using MULTIVALUED_STRING_TYPE would be better but it doesn't seem to
        // work
        if (config != null && !"".equals(config.trim())) {
            String[] configList = config.trim().split("&");
            String[] keyValue;
            for (String configEntry : configList) {
                keyValue = configEntry.split("=", 2);
                if (keyValue.length == 2) {
                    map.put(keyValue[0], keyValue[1]);
                }
            }
        }

        return map;
    }

    private Map<String, Object> getRequestParameters(ProtocolMapperModel mappingModel,
            UserSessionModel userSession,
            IDToken token) throws JsonProcessingException {
        // Get parameters
        final Map<String, Object> formattedParameters = buildMapFromStringConfig(
                mappingModel.getConfig().get(REMOTE_PARAMETERS));

        // TODO By default, only request absolute minimum claims needed for auth/ID
        // tokens (claims with PoP payload (client public key))
        // String claimReqType = "min_claims";
        // Right now, for back compat, ALWAYS return full claims by default - later,
        // when/if a reduced claimset is needed, we can default to minClaims
        String claimReqType = "full_claims";

        logger.debug("USERNAME: [{}], User ID: [{}], ", userSession.getLoginUsername(), userSession.getUser().getId());

        logger.debug("userSession.getNotes CONTENT IS: ");
        for (Map.Entry<String, String> entry : userSession.getNotes().entrySet()) {
            logger.debug("ENTRY IS: " + entry);
        }

        // AZP == clientID (always present)
        // SUB = subject (always present, might be == AZP, might not be )
        // Get client ID (or IDs plural, if this is a token that has been exchanged for
        // the same user from a previous client)
        String clientId = userSession.getAuthenticatedClientSessions().values().stream()
                .map(AuthenticatedClientSessionModel::getClient)
                .map(ClientModel::getId)
                .distinct()
                .collect(Collectors.joining(","));

        logger.debug("Complete list of clients from keycloak is: " + clientId);

        String[] clientIds = clientId.split(",");

        // Get username
        UserModel user = userSession.getUser();

        // Check if this is a service account user - if it is, this is direct-grant
        // auth, with no human user involved.
        // In that case, we don't care about the entity ID of the service account
        // Keycloak implicitly uses under the hood.
        // So ignore the service account user entity ID and just use the client entity
        // ID it's bound to, as the primary entity ID.
        //
        // For similar usage examples, see:
        // https://github.com/keycloak/keycloak/blob/99c06d11023689875b48ef56442c90bdb744c869/services/src/main/java/org/keycloak/exportimport/util/ExportUtils.java#L519
        if (user.getServiceAccountClientLink() != null) {
            logger.debug("User: " + userSession.getLoginUsername()
                    + " is a service account user, ignoring and using client ID in claims request");
            String clientInternalId = user.getServiceAccountClientLink();
            formattedParameters.put("primary_entity_id", clientInternalId);

            // This is dumb. If there's a terser and more efficient Java-y way to do this,
            // feel free to fix.
            List<String> clientlist = new ArrayList<String>(Arrays.asList(clientIds));
            clientlist.remove(clientInternalId);
            clientIds = clientlist.toArray(new String[0]);
        } else {
            formattedParameters.put("primary_entity_id", userSession.getUser().getId());
        }

        formattedParameters.put("secondary_entity_ids", clientIds);

        ObjectMapper objectMapper = new ObjectMapper();
        formattedParameters.put("entitlement_context_obj", objectMapper.writeValueAsString(token));

        logger.debug("CHECKING USERINFO mapper!");
        // If we are configured to be a protocol mapper for userinfo tokens, then always
        // include full claimset
        if (OIDCAttributeMapperHelper.includeInUserInfo(mappingModel)) {
            logger.debug("USERINFO mapper!");
            claimReqType = "full_claims";
        }

        return formattedParameters;
    }

    private JsonNode buildClaimsObject(JsonNode entitlements, String clientPublicKey) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode rootNode = mapper.createObjectNode();
        JsonNode val = mapper.valueToTree(entitlements);
        rootNode.set("entitlements", val);
        rootNode.put("client_public_signing_key", clientPublicKey);
        logger.debug("CLAIMSOBJ IS: " + rootNode.toPrettyString());
        return rootNode;
    }

    private String getClientPublicKey(ProtocolMapperModel mappingModel, KeycloakSession keycloakSession) {
        String clientPKHeaderName = mappingModel.getConfig().get(PUBLIC_KEY_HEADER);
        String clientPK = null;
        if (clientPKHeaderName != null) {
            List<String> clientPKList = keycloakSession.getContext().getRequestHeaders()
                    .getRequestHeader(clientPKHeaderName);
            clientPK = clientPKList == null || clientPKList.isEmpty() ? null : clientPKList.get(0);
        }
        if (clientPK != null) {
            if (clientPK.startsWith("LS0")) {
                byte[] decodedBytes = Base64.getDecoder().decode(clientPK);
                clientPK = new String(decodedBytes);
            }
            logger.debug("Client Cert: [{}]", clientPK);
        }
        if (clientPK == null) {
            logger.warn("No client cert presented in request, returning null");
            // noop - return
            return null;
        }

        return clientPK;
    }

    /**
     * Query Attribute-Provider for user's attributes.
     *
     * If no client public key has been provided in the request headers noop occurs.
     * Otherwise, a request
     * is sent as a simple map json document with keys:
     * - clientPublicSigningKey: the client's public signing key
     * - primaryEntityId: required - identifier for the principal subject claims are
     * being fetched for (PE or NPE)
     * - key/value per parameter configuration.
     * - secondaryEntityIds: required - list of identifiers for any additional
     * secondary subjects claims will be fetched for.
     * 
     * @param mappingModel
     * @param userSession
     * @param token
     * @return custom claims; null if no client pk present.
     */
    private JsonNode getRemoteAuthorizations(ProtocolMapperModel mappingModel, UserSessionModel userSession,
            IDToken token) {

        // Call remote service
        ResteasyProviderFactory instance = ResteasyProviderFactory.getInstance();
        RegisterBuiltin.register(instance);
        instance.registerProvider(ResteasyJackson2Provider.class);
        final String url = Strings.isNullOrEmpty(mappingModel.getConfig().get(REMOTE_URL)) ? System.getenv("CLAIMS_URL")
                : mappingModel.getConfig().get(REMOTE_URL);
        logger.info("Request attributes for subject: [{}] within [{}] from [{}]", token.getSubject(), token, url);
        CloseableHttpResponse response = null;
        try {
            // Get parameters
            Map<String, Object> parameters = getRequestParameters(mappingModel, userSession, token);
            // Get headers
            Map<String, Object> headers = getHeaders(mappingModel, userSession);
            headers.put("Content-Type", "application/json");

            if (url == null) {
                throw new Exception(REMOTE_URL + " property is not set via an env variable or configuration value");
            }

            HttpPost httpReq = new HttpPost(url);
            URIBuilder uriBuilder = new URIBuilder(httpReq.getURI());
            httpReq.setURI(uriBuilder.build());

            // Build parameters
            Map<String, Object> requestEntity = new HashMap<>(parameters);
            // requestEntity.put("algorithm", "ec:secp256r1");
            // requestEntity.put("clientPublicSigningKey", clientPK);

            // Build headers
            for (Map.Entry<String, Object> header : headers.entrySet()) {
                httpReq.setHeader(header.getKey(), header.getValue().toString());
            }
            ObjectMapper objectMapper = new ObjectMapper();
            httpReq.setEntity(new StringEntity(objectMapper.writeValueAsString(requestEntity)));

            logger.info("Request: " + requestEntity);
            response = client.execute(httpReq);
            String bodyAsString = EntityUtils.toString(response.getEntity());
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.warn(response.getStatusLine() + "" + bodyAsString);
                throw new Exception("Wrong status received for remote claim - Expected: 200, Received: "
                        + response.getStatusLine().getStatusCode() + ":" + url);
            }
            logger.debug(bodyAsString);
            return objectMapper.readValue(bodyAsString, JsonNode.class);
        } catch (Exception e) {
            logger.error("Error", e);
            // exceptions are thrown to prevent token from being delivered without all
            // information
            throw new JsonRemoteClaimException("Error when accessing remote claim", url, e);
        } finally {
            try {
                if (response != null) {
                    response.close();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
