package eu.europa.esig.dss.signature.policy.validation;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.europa.esig.dss.DSSException;
import eu.europa.esig.dss.cades.CMSUtils;
import eu.europa.esig.dss.cades.validation.CAdESSignature;
import eu.europa.esig.dss.signature.policy.CertInfoReq;
import eu.europa.esig.dss.signature.policy.CertRefReq;
import eu.europa.esig.dss.signature.policy.CertificateTrustPoint;
import eu.europa.esig.dss.signature.policy.CertificateTrustTrees;
import eu.europa.esig.dss.signature.policy.CommitmentRule;
import eu.europa.esig.dss.signature.policy.SignaturePolicy;
import eu.europa.esig.dss.signature.policy.SignatureValidationPolicy;
import eu.europa.esig.dss.signature.policy.SignerAndVerifierRules;
import eu.europa.esig.dss.signature.policy.SignerRules;
import eu.europa.esig.dss.signature.policy.SigningCertTrustCondition;
import eu.europa.esig.dss.signature.policy.VerifierRules;
import eu.europa.esig.dss.signature.policy.asn1.ASN1SignaturePolicy;
import eu.europa.esig.dss.signature.policy.validation.items.CAdESCertRefReqValidator;
import eu.europa.esig.dss.signature.policy.validation.items.CAdESSignerRulesExternalDataValidator;
import eu.europa.esig.dss.signature.policy.validation.items.CertInfoReqValidator;
import eu.europa.esig.dss.signature.policy.validation.items.CertificateTrustPointValidator;
import eu.europa.esig.dss.signature.policy.validation.items.CmsSignatureAttributesValidator;
import eu.europa.esig.dss.signature.policy.validation.items.ItemValidator;
import eu.europa.esig.dss.signature.policy.validation.items.RevReqValidator;
import eu.europa.esig.dss.signature.policy.validation.items.SignPolExtensionValidatorFactory;
import eu.europa.esig.dss.validation.BasicASNSignaturePolicyValidator;
import eu.europa.esig.dss.x509.CertificateToken;

/**
 * SignaturePolicy validation consists in matching the commitment rules with the given CMS blob
 * @author davyd.santos
 *
 */
public class FullCAdESSignaturePolicyValidator extends BasicASNSignaturePolicyValidator {

	private static final Logger LOG = LoggerFactory.getLogger(FullCAdESSignaturePolicyValidator.class);

	private Map<String, String> errors = new HashMap<String, String>();
	
	private Set<CertificateToken> signerCertPath = null;

	private SignaturePolicy policy;

	public FullCAdESSignaturePolicyValidator() {
	}

	public FullCAdESSignaturePolicyValidator(CAdESSignature sig) {
		setSignature(sig);
	}
	
	@Override
	public boolean canValidate() {
		return super.canValidate() && isCadesSignature();
	}
	
	@Override
	public void validate() {
		//  Upper class initializes the signature policy and validates the hash of the policy in 
		// the declared attribute and the value in the policy itself
		super.validate();
		
		// TODO Skip non processable ASN1
		if (getSignature().getPolicyId() != null && 
			getSignature().getPolicyId().getPolicyContent() != null &&
			isCadesSignature()) {
			validateSignaturePolicyCommitmentRules();
		}
	}

	private SignaturePolicy parse() {
		SignaturePolicy sigPolicy = null;
		try (
			InputStream is = getSignature().getPolicyId().getPolicyContent().openStream();
			ASN1InputStream asn1is = new ASN1InputStream(is);
		) {
			ASN1Primitive asn1SP = asn1is.readObject();
			if (asn1SP == null) {
				throw new DSSException("Error reading signature policy: no content");
			}
			sigPolicy = ASN1SignaturePolicy.getInstance(asn1SP);
		} catch (DSSException e) {
			throw e;
		} catch (IOException e) {
			// If the sigPolicy was loaded successfully, don't bubble up the error on stream close
			if (sigPolicy == null) {
				throw new DSSException("Error reading signature policy", e);
			}
		}
		return sigPolicy;
	}

	/**
	 * Validates signature based on a signature policy. It should not be called if
	 * No explicit signature police was declared upon signing.
	 */
	private void validateSignaturePolicyCommitmentRules() {
		ItemValidator itemValidator = SignPolExtensionValidatorFactory.createValidator(getSignature(), getSignatureValidationPolicy());
		if (!itemValidator.validate()) {
			errors.put("signatureValidationPolicy.signPolExtensions", "Error validating signature policy extension");
		}
		
		Set<CommitmentRule> cmmtRules = findCommitmentRule(getSignature().getCommitmentTypeIndication() == null? null: getSignature().getCommitmentTypeIndication().getIdentifiers());
		
		//TODO do I have to validate all or is it enough if one matching is found?
		for (CommitmentRule cmmtRule : cmmtRules) {
			validateSigningCertTrustContition(cmmtRule.getSigningCertTrustCondition());
			// TimestampTrustCondition 
			// AttributeTrustCondition
			// AlgorithmConstraintSet
			validateSignerAndVeriferRules(cmmtRule.getSignerAndVeriferRules());
			
			itemValidator = SignPolExtensionValidatorFactory.createValidator(getSignature(), cmmtRule);
			if (!itemValidator.validate()) {
				errors.put("commitmentRule.signPolExtensions", "Error validating signature policy extension");
			}
		}
	}

	private void validateSigningCertTrustContition(SigningCertTrustCondition signingCertTrustCondition) {
		RevReqValidator revReqValidator = new RevReqValidator(signingCertTrustCondition.getSignerRevReq().getEndCertRevReq(), getSignature().getSigningCertificateToken());
		if (!revReqValidator.validate()) {
			errors.put("signingCertTrustCondition.signerRevReq.endCertRevReq", "End certificate is revoked");
		}
		try {
			signerCertPath = buildTrustedCertificationPath(getSignature().getSigningCertificateToken(), signingCertTrustCondition.getSignerTrustTrees());
			if (signerCertPath.isEmpty()) {
				errors.put("signingCertTrustCondition.signerTrustTrees", "Could not build certification path to a trust point");
			}

			for (CertificateToken certificate : signerCertPath) {
				revReqValidator = new RevReqValidator(signingCertTrustCondition.getSignerRevReq().getCaCerts(), certificate);
				if (!revReqValidator.validate()) {
					errors.put("signingCertTrustCondition.signerRevReq.endCertRevReq", "One of the CA certificates is revoked");
					break;
				}
			}
		} catch (InvalidAlgorithmParameterException | NoSuchAlgorithmException | IOException e) {
			errors.put("signingCertTrustCondition", "unexpected error");
			LOG.warn("Error on validating signingCertTrustCondition", e);
		}
	}

	private Set<CertificateToken> buildTrustedCertificationPath(CertificateToken certificate, CertificateTrustTrees certificateTrustTrees) throws IOException, InvalidAlgorithmParameterException, NoSuchAlgorithmException {
		if (certificateTrustTrees == null || certificateTrustTrees.getCertificateTrustPoints().isEmpty()) {
			return CertificateTrustPointValidator.buildKnownChain(certificate);
		}
		
		CertStore certStore = CertificateTrustPointValidator.buildCertStore(certificate, getCadesSignature().getCertPool());
		for (CertificateTrustPoint trustPoint : certificateTrustTrees.getCertificateTrustPoints()) {
			CertificateTrustPointValidator trustPointValidator = new CertificateTrustPointValidator(getCadesSignature().getCertPool(), certStore, trustPoint);
			if (trustPointValidator.validate()) {
				return trustPointValidator.getChainCertificates();
			}
		}
		return Collections.emptySet();
	}
	private void validateSignerAndVeriferRules(SignerAndVerifierRules signerAndVeriferRules) {
		validateSignerRules(signerAndVeriferRules.getSignerRules());
		validateVerifierRules(signerAndVeriferRules.getVerifierRules());
	}

	private void validateSignerRules(SignerRules signerRules) {
		CAdESSignerRulesExternalDataValidator externalDataValidator = new CAdESSignerRulesExternalDataValidator(getCadesSignature(), signerRules.getExternalSignedData());
		if (!externalDataValidator.validate()) {
			errors.put("signerRules.externalSignedData", "Expected to be: " + signerRules.getExternalSignedData());
		}
		
		CmsSignatureAttributesValidator attributesValidator = new CmsSignatureAttributesValidator(signerRules.getMandatedSignedAttr(), CMSUtils.getSignedAttributes(getCadesSignature().getSignerInformation()));
		if (!attributesValidator.validate()) {
			errors.put("signerRules.mandatedSignedAttr", "Signed attributes missing: " + attributesValidator.getMissingAttributes());
		}
		attributesValidator = new CmsSignatureAttributesValidator(signerRules.getMandatedUnsignedAttr(), CMSUtils.getUnsignedAttributes(getCadesSignature().getSignerInformation()));
		if (!attributesValidator.validate()) {
			errors.put("signerRules.mandatedUnsignedAttr", "Unsigned attributes missing: " + attributesValidator.getMissingAttributes());
		}
		CAdESCertRefReqValidator certRefReqValidator = new CAdESCertRefReqValidator(signerRules.getMandatedCertificateRef(), getCadesSignature(), signerCertPath);
		if (!certRefReqValidator.validate()) {
			if (signerRules.getMandatedCertificateRef() == CertRefReq.signerOnly) {
				if (certRefReqValidator.containsAdditionalCertRef()) {
					errors.put("signerRules.mandatedCertificateRef", "Found more certificate references than expected");
				} else {
					errors.put("signerRules.mandatedCertificateRef", "No signing certificate reference found");
				}
			} else {
				errors.put("signerRules.mandatedCertificateRef", "Found less references than expected");
			}
		}
		
		if (!new CertInfoReqValidator(signerRules.getMandatedCertificateInfo(), getCadesSignature(), signerCertPath).validate()) {
			if (signerRules.getMandatedCertificateInfo() == CertInfoReq.none) {
				errors.put("signerRules.mandatedCertificateInfo", "Should not have any certificates in the signature");
			} else if (signerRules.getMandatedCertificateInfo() == CertInfoReq.signerOnly) {
				errors.put("signerRules.mandatedCertificateInfo", "Should have only the signer certificate in the signature");
			} else if (signerRules.getMandatedCertificateInfo() == CertInfoReq.fullPath) {
				errors.put("signerRules.mandatedCertificateInfo", "Should have the signer certificate full path in the signature");
			}
		}
		
		ItemValidator itemValidator = SignPolExtensionValidatorFactory.createValidator(getSignature(), signerRules);
		if (!itemValidator.validate()) {
			errors.put("signerRules.signPolExtensions", "Error validating signature policy extension");
		}
	}

	private void validateVerifierRules(VerifierRules verifierRules) {
		CmsSignatureAttributesValidator attributesValidator = new CmsSignatureAttributesValidator(verifierRules.getMandatedUnsignedAttr(), CMSUtils.getUnsignedAttributes(getCadesSignature().getSignerInformation()));
		if (!attributesValidator.validate()) {
			errors.put("verifierRules.mandatedUnsignedAttr", "Unsigned attributes missing: " + attributesValidator.getMissingAttributes());
		}
		
		ItemValidator itemValidator = SignPolExtensionValidatorFactory.createValidator(getSignature(), verifierRules);
		if (!itemValidator.validate()) {
			errors.put("verifierRules.signPolExtensions", "Error validating signature policy extension");
		}
	}

	public Set<CommitmentRule> findCommitmentRule(List<String> identifiers) {
		Set<CommitmentRule> commtRules = new LinkedHashSet<CommitmentRule>();
		SignatureValidationPolicy signatureValidationPolicy = getSignatureValidationPolicy();
		for (CommitmentRule cmmtRule : signatureValidationPolicy.getCommitmentRules()) {
			if (identifiers == null || identifiers.isEmpty()) {
				if (cmmtRule.getSelCommitmentTypes().contains(null)) {
					commtRules.add(new CommitmentRuleWrapper(cmmtRule, signatureValidationPolicy.getCommonRules()));
				} else {
					// RFC 3125
					// "... the electronic signature must contain a commitment type indication
					// that must fit one of the commitments types that are mentioned in
					// CommitmentType."
					throw new DSSException("The commitment type used was not found");
				}
			} else {
				for (String oid : identifiers) {
					if (cmmtRule.getSelCommitmentTypes().contains(oid)) {
						commtRules.add(new CommitmentRuleWrapper(cmmtRule, signatureValidationPolicy.getCommonRules())); 
					}
				}
			}
		}
		
		if (commtRules.isEmpty()) {
			// RFC 3125
			// "... the electronic signature must contain a commitment type indication
			// that must fit one of the commitments types that are mentioned in
			// CommitmentType."
			throw new DSSException("The commitment type used was not found");
		}
		return commtRules;
	}

	public SignatureValidationPolicy getSignatureValidationPolicy() {
		if (policy == null) {
			policy = parse();
		}
		SignatureValidationPolicy signatureValidationPolicy = policy.getSignPolicyInfo().getSignatureValidationPolicy();
		return signatureValidationPolicy;
	}

	private CAdESSignature getCadesSignature() {
		return (CAdESSignature) getSignature();
	}

	private boolean isCadesSignature() {
		return getSignature() instanceof CAdESSignature;
	}

	
}
