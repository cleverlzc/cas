package org.apereo.cas.support.wsfederation;

import com.google.common.base.Predicates;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.shibboleth.utilities.java.support.resolver.CriteriaSet;
import org.apache.commons.lang3.tuple.Pair;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.services.RegisteredServiceAccessStrategyUtils;
import org.apereo.cas.services.RegisteredServiceProperty;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.saml.OpenSamlConfigBean;
import org.apereo.cas.support.saml.SamlUtils;
import org.apereo.cas.support.wsfederation.authentication.principal.WsFederationCredential;
import org.apereo.cas.util.function.FunctionUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.provider.X509CertParser;
import org.bouncycastle.jce.provider.X509CertificateObject;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.jooq.lambda.Unchecked;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.schema.XSAny;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.criterion.EntityRoleCriterion;
import org.opensaml.saml.criterion.ProtocolCriterion;
import org.opensaml.saml.saml1.core.Assertion;
import org.opensaml.saml.saml2.encryption.Decrypter;
import org.opensaml.saml.saml2.encryption.EncryptedElementTypeEncryptedKeyResolver;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.UsageType;
import org.opensaml.security.credential.impl.StaticCredentialResolver;
import org.opensaml.security.criteria.UsageCriterion;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.soap.wsfed.RequestSecurityTokenResponse;
import org.opensaml.soap.wsfed.RequestedSecurityToken;
import org.opensaml.xmlsec.encryption.EncryptedData;
import org.opensaml.xmlsec.encryption.support.ChainingEncryptedKeyResolver;
import org.opensaml.xmlsec.encryption.support.EncryptedKeyResolver;
import org.opensaml.xmlsec.encryption.support.InlineEncryptedKeyResolver;
import org.opensaml.xmlsec.encryption.support.SimpleRetrievalMethodEncryptedKeyResolver;
import org.opensaml.xmlsec.keyinfo.impl.StaticKeyInfoCredentialResolver;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.SignatureTrustEngine;
import org.opensaml.xmlsec.signature.support.impl.ExplicitKeySignatureTrustEngine;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Helper class that does the heavy lifting with the openSaml library.
 *
 * @author John Gasper
 * @since 4.2.0
 */
@Slf4j
@Setter
@RequiredArgsConstructor
public class WsFederationHelper {

    private final OpenSamlConfigBean configBean;
    private final ServicesManager servicesManager;

    /**
     * createCredentialFromToken converts a SAML 1.1 assertion to a WSFederationCredential.
     *
     * @param assertion the provided assertion
     * @return an equivalent credential.
     */
    public WsFederationCredential createCredentialFromToken(final Assertion assertion) {
        val retrievedOn = ZonedDateTime.now();
        LOGGER.debug("Retrieved on [{}]", retrievedOn);
        val credential = new WsFederationCredential();
        credential.setRetrievedOn(retrievedOn);
        credential.setId(assertion.getID());
        credential.setIssuer(assertion.getIssuer());
        credential.setIssuedOn(ZonedDateTime.parse(assertion.getIssueInstant().toDateTimeISO().toString()));
        val conditions = assertion.getConditions();
        if (conditions != null) {
            credential.setNotBefore(ZonedDateTime.parse(conditions.getNotBefore().toDateTimeISO().toString()));
            credential.setNotOnOrAfter(ZonedDateTime.parse(conditions.getNotOnOrAfter().toDateTimeISO().toString()));
            if (!conditions.getAudienceRestrictionConditions().isEmpty()) {
                credential.setAudience(conditions.getAudienceRestrictionConditions().get(0).getAudiences().get(0).getUri());
            }
        }
        if (!assertion.getAuthenticationStatements().isEmpty()) {
            credential.setAuthenticationMethod(assertion.getAuthenticationStatements().get(0).getAuthenticationMethod());
        }
        //retrieve an attributes from the assertion
        val attributes = new HashMap<String, List<Object>>();
        assertion.getAttributeStatements().stream().flatMap(attributeStatement -> attributeStatement.getAttributes().stream()).forEach(item -> {
            LOGGER.debug("Processed attribute: [{}]", item.getAttributeName());
            final List<Object> itemList = IntStream.range(0, item.getAttributeValues().size())
                .mapToObj(i -> ((XSAny) item.getAttributeValues().get(i)).getTextContent()).collect(Collectors.toList());
            if (!itemList.isEmpty()) {
                attributes.put(item.getAttributeName(), itemList);
            }
        });
        credential.setAttributes(attributes);
        LOGGER.debug("Credential: [{}]", credential);
        return credential;
    }

    /**
     * Gets request security token response from result.
     *
     * @param wresult the wresult
     * @return the request security token response from result
     */
    public RequestedSecurityToken getRequestSecurityTokenFromResult(final String wresult) {
        LOGGER.debug("Result token received from ADFS is [{}]", wresult);
        try (InputStream in = new ByteArrayInputStream(wresult.getBytes(StandardCharsets.UTF_8))) {
            LOGGER.debug("Parsing token into a document");
            val document = configBean.getParserPool().parse(in);
            val metadataRoot = document.getDocumentElement();
            val unmarshallerFactory = configBean.getUnmarshallerFactory();
            val unmarshaller = unmarshallerFactory.getUnmarshaller(metadataRoot);
            if (unmarshaller == null) {
                throw new IllegalArgumentException("Unmarshaller for the metadata root element cannot be determined");
            }
            LOGGER.debug("Unmarshalling the document into a security token response");
            val rsToken = (RequestSecurityTokenResponse) unmarshaller.unmarshall(metadataRoot);
            if (rsToken.getRequestedSecurityToken() == null) {
                throw new IllegalArgumentException("Request security token response is null");
            }
            LOGGER.debug("Locating list of requested security tokens");
            val rst = rsToken.getRequestedSecurityToken();
            if (rst.isEmpty()) {
                throw new IllegalArgumentException("No requested security token response is provided in the response");
            }
            LOGGER.debug("Locating the first occurrence of a requested security token in the list");
            val reqToken = rst.get(0);
            if (reqToken.getSecurityTokens() == null || reqToken.getSecurityTokens().isEmpty()) {
                throw new IllegalArgumentException("Requested security token response is not carrying any security tokens");
            }
            return reqToken;
        } catch (final Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
        }
        return null;
    }

    /**
     * converts a token into an assertion.
     *
     * @param reqToken the req token
     * @param config   the config
     * @return an assertion
     */
    public Pair<Assertion, WsFederationConfiguration> buildAndVerifyAssertion(final RequestedSecurityToken reqToken, final Collection<WsFederationConfiguration> config) {
        val securityToken = getSecurityTokenFromRequestedToken(reqToken, config);
        if (securityToken instanceof Assertion) {
            LOGGER.debug("Security token is an assertion.");
            val assertion = Assertion.class.cast(securityToken);
            LOGGER.debug("Extracted assertion successfully: [{}]", assertion);
            val cfg = config.stream()
                .filter(c -> c.getIdentityProviderIdentifier().equals(assertion.getIssuer()))
                .findFirst()
                .orElse(null);
            if (cfg == null) {
                throw new IllegalArgumentException("Could not locate wsfed configuration for security token provided. The assertion issuer "
                    + assertion.getIssuer() + " does not match any of the identity provider identifiers defined in the configuration");
            }
            return Pair.of(assertion, cfg);
        }
        throw new IllegalArgumentException("Could not extract or decrypt an assertion based on the security token provided");
    }

    private XMLObject getSecurityTokenFromRequestedToken(final RequestedSecurityToken reqToken, final Collection<WsFederationConfiguration> config) {
        LOGGER.debug("Locating the first occurrence of a security token from the requested security token");
        val securityTokenFromAssertion = getAssertionFromSecurityToken(reqToken);

        val func = FunctionUtils.doIf(Predicates.instanceOf(EncryptedData.class),
            () -> {
                LOGGER.debug("Security token is encrypted. Attempting to decrypt to extract the assertion");
                val encryptedData = EncryptedData.class.cast(securityTokenFromAssertion);
                val it = config.iterator();
                while (it.hasNext()) {
                    try {
                        val c = it.next();
                        val decrypter = buildAssertionDecrypter(c);
                        LOGGER.debug("Built an instance of [{}]", decrypter.getClass().getName());
                        var decryptedToken = decrypter.decryptData(encryptedData);
                        LOGGER.debug("Decrypted assertion successfully");
                        return decryptedToken;
                    } catch (final Exception e) {
                        LOGGER.debug(e.getMessage(), e);
                    }
                }
                LOGGER.error("Could not extract or decrypt an assertion based on the security token provided");
                return null;
            },
            () -> securityTokenFromAssertion);

        @NonNull val securityToken = func.apply(securityTokenFromAssertion);
        return securityToken;
    }

    /**
     * Gets assertion from security token.
     *
     * @param reqToken the req token
     * @return the assertion from security token
     */
    public XMLObject getAssertionFromSecurityToken(final RequestedSecurityToken reqToken) {
        return reqToken.getSecurityTokens().get(0);
    }

    /**
     * validateSignature checks to see if the signature on an assertion is valid.
     *
     * @param assertion a provided assertion
     * @return true if the assertion's signature is valid, otherwise false
     */
    public boolean validateSignature(final Pair<Assertion, WsFederationConfiguration> assertion) {
        if (assertion == null) {
            LOGGER.warn("No assertion or its configuration was provided to validate signatures");
            return false;
        }
        val value = assertion.getValue();
        val key = assertion.getKey();
        if (key == null || value == null) {
            LOGGER.warn("No signature or configuration was provided to validate signatures");
            return false;
        }

        val signature = key.getSignature();
        SamlUtils.logSamlObject(this.configBean, assertion.getKey());
        if (signature != null) {
            return false;
        }

        val validator = new SAMLSignatureProfileValidator();
        try {
            LOGGER.debug("Validating signature...");
            validator.validate(signature);
            val criteriaSet = new CriteriaSet();
            criteriaSet.add(new UsageCriterion(UsageType.SIGNING));
            criteriaSet.add(new EntityRoleCriterion(IDPSSODescriptor.DEFAULT_ELEMENT_NAME));
            criteriaSet.add(new ProtocolCriterion(SAMLConstants.SAML20P_NS));
            criteriaSet.add(new EntityIdCriterion(value.getIdentityProviderIdentifier()));
            try {
                val engine = buildSignatureTrustEngine(value);
                LOGGER.debug("Validating signature via trust engine for [{}]", value.getIdentityProviderIdentifier());
                return engine.validate(signature, criteriaSet);
            } catch (final SecurityException e) {
                LOGGER.warn(e.getMessage(), e);
            }
        } catch (final SignatureException e) {
            LOGGER.error("Failed to validate assertion signature", e);
        }

        LOGGER.error("Signature doesn't match any signing credential.");
        return false;
    }

    /**
     * Get the relying party id for a service.
     *
     * @param service       the service to get an id for
     * @param configuration the configuration
     * @return relying party id
     */
    public String getRelyingPartyIdentifier(final Service service, final WsFederationConfiguration configuration) {
        val relyingPartyIdentifier = configuration.getRelyingPartyIdentifier();
        if (service != null) {
            val registeredService = this.servicesManager.findServiceBy(service);
            RegisteredServiceAccessStrategyUtils.ensureServiceAccessIsAllowed(service, registeredService);
            if (RegisteredServiceProperty.RegisteredServiceProperties.WSFED_RELYING_PARTY_ID.isAssignedTo(registeredService)) {
                LOGGER.debug("Determined relying party identifier from [{}] to be [{}]", service, relyingPartyIdentifier);
                return RegisteredServiceProperty.RegisteredServiceProperties.WSFED_RELYING_PARTY_ID.getPropertyValue(registeredService).getValue();
            }
        }
        LOGGER.debug("Determined relying party identifier for [{}] to be [{}]", service, relyingPartyIdentifier);
        return relyingPartyIdentifier;
    }

    /**
     * Build signature trust engine.
     *
     * @param wsFederationConfiguration the ws federation configuration
     * @return the signature trust engine
     */
    @SneakyThrows
    private static SignatureTrustEngine buildSignatureTrustEngine(final WsFederationConfiguration wsFederationConfiguration) {
        val signingWallet = wsFederationConfiguration.getSigningWallet();
        val resolver = new StaticCredentialResolver(signingWallet);
        val keyResolver = new StaticKeyInfoCredentialResolver(signingWallet);
        return new ExplicitKeySignatureTrustEngine(resolver, keyResolver);
    }

    @SneakyThrows
    private static Credential getEncryptionCredential(final WsFederationConfiguration config) {
        // This will need to contain the private keypair in PEM format
        LOGGER.debug("Locating encryption credential private key [{}]", config.getEncryptionPrivateKey());
        val br = new BufferedReader(new InputStreamReader(config.getEncryptionPrivateKey().getInputStream(), StandardCharsets.UTF_8));
        Security.addProvider(new BouncyCastleProvider());
        LOGGER.debug("Parsing credential private key");
        try (val pemParser = new PEMParser(br)) {
            val privateKeyPemObject = pemParser.readObject();
            val converter = new JcaPEMKeyConverter().setProvider(new BouncyCastleProvider());

            val kp = FunctionUtils.doIf(Predicates.instanceOf(PEMEncryptedKeyPair.class),
                Unchecked.supplier(() -> {
                    LOGGER.debug("Encryption private key is an encrypted keypair");
                    val ckp = (PEMEncryptedKeyPair) privateKeyPemObject;
                    val decProv = new JcePEMDecryptorProviderBuilder().build(config.getEncryptionPrivateKeyPassword().toCharArray());
                    LOGGER.debug("Attempting to decrypt the encrypted keypair based on the provided encryption private key password");
                    return converter.getKeyPair(ckp.decryptKeyPair(decProv));
                }),
                Unchecked.supplier(() -> {
                    LOGGER.debug("Extracting a keypair from the private key");
                    return converter.getKeyPair((PEMKeyPair) privateKeyPemObject);
                }))
                .apply(privateKeyPemObject);

            val certParser = new X509CertParser();
            // This is the certificate shared with ADFS in DER format, i.e certificate.crt
            LOGGER.debug("Locating encryption certificate [{}]", config.getEncryptionCertificate());
            certParser.engineInit(config.getEncryptionCertificate().getInputStream());
            LOGGER.debug("Invoking certificate engine to parse the certificate [{}]", config.getEncryptionCertificate());
            val cert = (X509CertificateObject) certParser.engineRead();
            LOGGER.debug("Creating final credential based on the certificate [{}] and the private key", cert.getIssuerDN());
            return new BasicX509Credential(cert, kp.getPrivate());
        }

    }

    private static Decrypter buildAssertionDecrypter(final WsFederationConfiguration config) {
        val list = new ArrayList<EncryptedKeyResolver>();
        list.add(new InlineEncryptedKeyResolver());
        list.add(new EncryptedElementTypeEncryptedKeyResolver());
        list.add(new SimpleRetrievalMethodEncryptedKeyResolver());
        LOGGER.debug("Built a list of encrypted key resolvers: [{}]", list);
        val encryptedKeyResolver = new ChainingEncryptedKeyResolver(list);
        LOGGER.debug("Building credential instance to decrypt data");
        val encryptionCredential = getEncryptionCredential(config);
        val resolver = new StaticKeyInfoCredentialResolver(encryptionCredential);
        val decrypter = new Decrypter(null, resolver, encryptedKeyResolver);
        decrypter.setRootInNewDocument(true);
        return decrypter;
    }
}
