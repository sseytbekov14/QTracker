package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.security.SsoIdentity;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.AttributeValue;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SAML answers of the test identity provider (src/test/resources/saml), signed as Microsoft Entra ID signs them
 * (the assertion, RSA-SHA256), for the Assertion Consumer Service of SsoTestSupport. Only what a sign-in needs:
 * NameID, the Entra claims (e-mail, name = UPN, object id), audience, recipient, the request answered.
 */
final class SamlResponses {

    private static final BasicX509Credential CREDENTIAL;

    static {
        OpenSamlInitializationService.initialize();
        CREDENTIAL = new BasicX509Credential(certificate(), privateKey());
    }

    private SamlResponses() {
    }

    /** The person as Microsoft would describe them; null leaves the value out of the answer. */
    record Person(String nameId, String email, String upn, String objectId) {

        static Person of(String email, String objectId) {
            return new Person(email, email, email, objectId);
        }
    }

    /** A signed, base64 answer to the request {@code inResponseTo} (null: an answer nobody asked for). */
    static String signed(Person person, String inResponseTo) {
        return signed(person, inResponseTo, SsoTestSupport.ENTITY_ID);
    }

    static String signed(Person person, String inResponseTo, String audience) {
        Instant now = Instant.now();
        Response response = build(Response.DEFAULT_ELEMENT_NAME);
        response.setID("_" + UUID.randomUUID());
        response.setIssueInstant(now);
        response.setVersion(SAMLVersion.VERSION_20);
        response.setDestination(SsoTestSupport.ACS_URL);
        response.setInResponseTo(inResponseTo);
        response.setIssuer(issuer());
        Status status = build(Status.DEFAULT_ELEMENT_NAME);
        StatusCode code = build(StatusCode.DEFAULT_ELEMENT_NAME);
        code.setValue(StatusCode.SUCCESS);
        status.setStatusCode(code);
        response.setStatus(status);

        Assertion assertion = build(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setID("_" + UUID.randomUUID());
        assertion.setIssueInstant(now);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssuer(issuer());
        assertion.setSubject(subject(person, inResponseTo, now));
        assertion.setConditions(conditions(audience, now));
        assertion.getAuthnStatements().add(authnStatement(now));
        assertion.getAttributeStatements().add(attributes(person));
        response.getAssertions().add(assertion);

        Signature signature = build(Signature.DEFAULT_ELEMENT_NAME);
        signature.setSigningCredential(CREDENTIAL);
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        assertion.setSignature(signature);
        try {
            Element element = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                    .getMarshaller(response).marshall(response);
            Signer.signObject(signature);
            return Base64.getEncoder().encodeToString(serialize(element).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot sign the test answer", ex);
        }
    }

    private static Issuer issuer() {
        Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(SsoTestSupport.IDP_ENTITY_ID);
        return issuer;
    }

    private static Subject subject(Person person, String inResponseTo, Instant now) {
        Subject subject = build(Subject.DEFAULT_ELEMENT_NAME);
        if (person.nameId() != null) {
            NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
            nameId.setValue(person.nameId());
            nameId.setFormat(NameID.EMAIL);
            subject.setNameID(nameId);
        }
        SubjectConfirmation confirmation = build(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        SubjectConfirmationData data = build(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
        data.setRecipient(SsoTestSupport.ACS_URL);
        data.setNotOnOrAfter(now.plus(Duration.ofMinutes(5)));
        data.setInResponseTo(inResponseTo);
        confirmation.setSubjectConfirmationData(data);
        subject.getSubjectConfirmations().add(confirmation);
        return subject;
    }

    private static Conditions conditions(String audienceUri, Instant now) {
        Conditions conditions = build(Conditions.DEFAULT_ELEMENT_NAME);
        conditions.setNotBefore(now.minus(Duration.ofMinutes(1)));
        conditions.setNotOnOrAfter(now.plus(Duration.ofMinutes(5)));
        AudienceRestriction restriction = build(AudienceRestriction.DEFAULT_ELEMENT_NAME);
        Audience audience = build(Audience.DEFAULT_ELEMENT_NAME);
        audience.setURI(audienceUri);
        restriction.getAudiences().add(audience);
        conditions.getAudienceRestrictions().add(restriction);
        return conditions;
    }

    private static AuthnStatement authnStatement(Instant now) {
        AuthnStatement statement = build(AuthnStatement.DEFAULT_ELEMENT_NAME);
        statement.setAuthnInstant(now);
        statement.setSessionIndex("_session-" + UUID.randomUUID());
        AuthnContext context = build(AuthnContext.DEFAULT_ELEMENT_NAME);
        AuthnContextClassRef classRef = build(AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
        classRef.setURI(AuthnContext.PPT_AUTHN_CTX);
        context.setAuthnContextClassRef(classRef);
        statement.setAuthnContext(context);
        return statement;
    }

    private static AttributeStatement attributes(Person person) {
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put(SsoIdentity.OBJECT_ID_CLAIM, person.objectId());
        claims.put(SsoIdentity.EMAIL_CLAIM, person.email());
        claims.put(SsoIdentity.NAME_CLAIM, person.upn());
        claims.put("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname", "Test");
        AttributeStatement statement = build(AttributeStatement.DEFAULT_ELEMENT_NAME);
        claims.forEach((name, value) -> {
            if (value == null) {
                return;
            }
            Attribute attribute = build(Attribute.DEFAULT_ELEMENT_NAME);
            attribute.setName(name);
            XSString text = (XSString) XMLObjectProviderRegistrySupport.getBuilderFactory()
                    .getBuilder(XSString.TYPE_NAME).buildObject(AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME);
            text.setValue(value);
            attribute.getAttributeValues().add(text);
            statement.getAttributes().add(attribute);
        });
        return statement;
    }

    @SuppressWarnings("unchecked")
    private static <T extends XMLObject> T build(QName name) {
        XMLObjectBuilderFactory factory = XMLObjectProviderRegistrySupport.getBuilderFactory();
        return (T) factory.getBuilder(name).buildObject(name);
    }

    private static String serialize(Element element) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(out));
        return out.toString();
    }

    private static X509Certificate certificate() {
        try (InputStream in = new ClassPathResource("saml/test-idp.crt").getInputStream()) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static PrivateKey privateKey() {
        try (InputStream in = new ClassPathResource("saml/test-idp.key").getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
