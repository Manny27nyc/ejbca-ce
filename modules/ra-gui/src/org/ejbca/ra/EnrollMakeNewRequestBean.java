/*************************************************************************
 *                                                                       *
 *  EJBCA Community: The OpenSource Certificate Authority                *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.ejbca.ra;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StringReader;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.faces.application.FacesMessage;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ManagedProperty;
import javax.faces.bean.ViewScoped;
import javax.faces.component.UIComponent;
import javax.faces.component.UIInput;
import javax.faces.component.html.HtmlSelectOneMenu;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.faces.event.AjaxBehaviorEvent;
import javax.faces.event.ComponentSystemEvent;
import javax.faces.model.SelectItem;
import javax.faces.validator.ValidatorException;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.apache.myfaces.custom.fileupload.UploadedFile;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.util.encoders.Hex;
import org.cesecore.ErrorCode;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.certificates.ca.ApprovalRequestType;
import org.cesecore.certificates.ca.CAInfo;
import org.cesecore.certificates.ca.X509CAInfo;
import org.cesecore.certificates.certificate.certextensions.standard.CabForumOrganizationIdentifier;
import org.cesecore.certificates.certificate.certextensions.standard.QcStatement;
import org.cesecore.certificates.certificateprofile.CertificateProfile;
import org.cesecore.certificates.endentity.EndEntityConstants;
import org.cesecore.certificates.endentity.EndEntityInformation;
import org.cesecore.certificates.endentity.EndEntityType;
import org.cesecore.certificates.endentity.EndEntityTypes;
import org.cesecore.certificates.endentity.ExtendedInformation;
import org.cesecore.certificates.endentity.PSD2RoleOfPSPStatement;
import org.cesecore.certificates.util.AlgorithmConstants;
import org.cesecore.certificates.util.AlgorithmTools;
import org.cesecore.certificates.util.DnComponents;
import org.cesecore.certificates.util.cert.SubjectDirAttrExtension;
import org.cesecore.config.CesecoreConfiguration;
import org.cesecore.config.EABConfiguration;
import org.cesecore.keys.util.KeyTools;
import org.cesecore.util.CeSecoreNameStyle;
import org.cesecore.util.CertTools;
import org.cesecore.util.PrintableStringNameStyle;
import org.cesecore.util.StringTools;
import org.cesecore.util.ValidityDate;
import org.ejbca.core.EjbcaException;
import org.ejbca.core.model.SecConst;
import org.ejbca.core.model.TokenDownloadType;
import org.ejbca.core.model.approval.WaitingForApprovalException;
import org.ejbca.core.model.authorization.AccessRulesConstants;
import org.ejbca.core.model.era.IdNameHashMap;
import org.ejbca.core.model.era.KeyToValueHolder;
import org.ejbca.core.model.era.RaMasterApiProxyBeanLocal;
import org.ejbca.core.model.ra.raadmin.EndEntityProfile;
import org.ejbca.core.model.ra.raadmin.EndEntityProfile.FieldInstance;
import org.ejbca.util.cert.OID;

/**
 * Managed bean that backs up the enrollingmakenewrequest.xhtml page.
 * <p>
 * DEVELOPER NOTE:
 * Since the page this bean backs up has pretty advanced dependencies for what should be rendered when,
 * an unconventional pattern is used where getters will calculate their current value based on the
 * current state of their dependencies.
 * <p>
 * (The normal pattern would be that changes/actions should calculate and modify everything that is
 * effected by this change. This would be harder to code and maintain since you have to think about
 * all permutations that could potentially be affected down the line.)
 *
 */
@ManagedBean
@ViewScoped
public class EnrollMakeNewRequestBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(EnrollMakeNewRequestBean.class);

    private static final String ENROLL_USERNAME_ALREADY_EXISTS = "enroll_username_already_exists";
    private static final String ENROLL_INVALID_CERTIFICATE_REQUEST = "enroll_invalid_certificate_request";
    private static final String ENROLL_SELECT_KA_NOCHOICE = "enroll_select_ka_nochoice";
    private static final String APPLICATION_X_PKCS12 = "application/x-pkcs12";
    private static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public static String PARAM_REQUESTID = "requestId";
    public static int MAX_CSR_LENGTH = 10240;

    @EJB
    private RaMasterApiProxyBeanLocal raMasterApiProxyBean;

    @ManagedProperty(value = "#{raAuthenticationBean}")
    private RaAuthenticationBean raAuthenticationBean;

    public void setRaAuthenticationBean(final RaAuthenticationBean raAuthenticationBean) {
        this.raAuthenticationBean = raAuthenticationBean;
    }

    @ManagedProperty(value = "#{raLocaleBean}")
    private RaLocaleBean raLocaleBean;

    public void setRaLocaleBean(final RaLocaleBean raLocaleBean) {
        this.raLocaleBean = raLocaleBean;
    }

    private boolean renderNonModifiableTemplates = false;
    private boolean renderNonModifiableFields = false;
    private IdNameHashMap<EndEntityProfile> authorizedEndEntityProfiles = new IdNameHashMap<>();
    private IdNameHashMap<CertificateProfile> authorizedCertificateProfiles = new IdNameHashMap<>();
    private IdNameHashMap<CAInfo> authorizedCAInfos = new IdNameHashMap<>();
    private String selectedEndEntityProfile;
    private String selectedCertificateProfile;
    private String selectedCertificateAuthority;
    private String validity = StringUtils.EMPTY;
    private EABConfiguration eabConfiguration;

    /**
     * Is private key generated by the server (CA) or is the key provided by the user (usually in the form of a CSR)
     */
    public enum KeyPairGeneration {
        ON_SERVER,
        PROVIDED_BY_USER,
        POSTPONE;
    }

    private KeyPairGeneration selectedKeyPairGeneration;
    /**
     * Heavy to calculate and needs to be cached.
     */
    private List<SelectItem> availableAlgorithmSelectItems = null;

    private List<String> selectedPsd2PspRoles;
    private String psd2NcaId;
    private String psd2NcaName;
    private String cabfOrganizationIdentifier;
    private String selectedAlgorithm; //GENERATED ON SERVER
    private String algorithmFromCsr; //PROVIDED BY USER
    private int selectedTokenType;
    private UploadedFile uploadFile;
    private String certificateRequest;
    private String publicKeyModulus;
    private String publicKeyExponent;
    private String sha256Fingerprint;
    private String signature;
    private String csrFileName;
    private String extensionData;
    private String accountBindingId;
    private SubjectDn subjectDn;
    private SubjectAlternativeName subjectAlternativeName;
    private SubjectDirectoryAttributes subjectDirectoryAttributes;
    private EndEntityInformation endEntityInformation;
    private String confirmPassword;
    private int requestId;
    private boolean requestPreviewMoreDetails;
    private boolean setCustomValidity;
    private UIComponent subjectDnMessagesComponent;
    private UIComponent userCredentialsMessagesComponent;
    private UIComponent confirmPasswordComponent;
    private UIComponent validityInputComponent;

    @PostConstruct
    private void postContruct() {
        this.authorizedEndEntityProfiles = raMasterApiProxyBean.getAuthorizedEndEntityProfiles(raAuthenticationBean.getAuthenticationToken(), AccessRulesConstants.CREATE_END_ENTITY);
        this.authorizedCertificateProfiles = raMasterApiProxyBean.getAllAuthorizedCertificateProfiles(raAuthenticationBean.getAuthenticationToken());
        this.authorizedCAInfos = raMasterApiProxyBean.getAuthorizedCAInfos(raAuthenticationBean.getAuthenticationToken());
    }

    //-----------------------------------------------------------------------------------------------
    // Helpers and is*Rendered() methods

    public boolean isRequestIdInfoRendered() {
        return requestId != 0;
    }

    public boolean isUsernameRendered() {
        return !getEndEntityProfile().isAutoGeneratedUsername();
    }

    public boolean isPasswordRendered() {
        return getSelectedKeyPairGenerationEnum() != null &&
                (isApprovalRequired() ||
                        KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum()) ||
                        KeyPairGeneration.POSTPONE.equals(getSelectedKeyPairGenerationEnum())) &&
                !getEndEntityProfile().useAutoGeneratedPasswd();
    }

    public boolean isEmailRendered() {
        return getEndEntityProfile().getUse(EndEntityProfile.EMAIL, 0);
    }

    public boolean isEmailRequired() {
        return getEndEntityProfile().isRequired(EndEntityProfile.SENDNOTIFICATION, 0) ||
                getEndEntityProfile().isRequired(EndEntityProfile.EMAIL, 0);
    }

    /**
     * @return true if 'Add End Entity' button should be rendered
     */
    public boolean isAddEndEntityButtonRendered() {
        return !isApprovalRequired() && KeyPairGeneration.POSTPONE.equals(getSelectedKeyPairGenerationEnum());
    }

    /**
     * @return true if keystore download options in JKS format should be provided (e.g. keystore generation was used and no approvals are required)
     */
    public boolean isGenerateJksButtonRendered() {
        EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile == null) {
            return false;
        }
        String availableKeyStores = endEntityProfile.getValue(EndEntityProfile.AVAILKEYSTORE, 0);
        return availableKeyStores != null && availableKeyStores.contains(String.valueOf(SecConst.TOKEN_SOFT_JKS))
                && getSelectedKeyPairGenerationEnum() != null && KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())
                && !isApprovalRequired();
    }

    /**
     * @return true if keystore download options in PKCS#12 format should be provided (e.g. keystore generation was used and no approvals are required)
     */
    public boolean isGenerateP12ButtonRendered() {
        EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile == null) {
            return false;
        }
        String availableKeyStores = endEntityProfile.getValue(EndEntityProfile.AVAILKEYSTORE, 0);
        return availableKeyStores != null && availableKeyStores.contains(String.valueOf(SecConst.TOKEN_SOFT_P12))
                && getSelectedKeyPairGenerationEnum() != null && KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())
                && !isApprovalRequired();
    }

    /**
     * @return true if keystore download options in FIPS PKCS#12 format should be provided (e.g. keystore generation was used and no approvals are required)
     */
    public boolean isgenerateBcfksButtonRendered() {
        EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile == null) {
            return false;
        }
        String availableKeyStores = endEntityProfile.getValue(EndEntityProfile.AVAILKEYSTORE, 0);
        return availableKeyStores != null && availableKeyStores.contains(String.valueOf(SecConst.TOKEN_SOFT_BCFKS))
                && getSelectedKeyPairGenerationEnum() != null && KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())
                && !isApprovalRequired();
    }

    /**
     * @return true if keystore download options in PEM format should be provided (e.g. keystore generation was used and no approvals are required)
     */
    public boolean isGeneratePemButtonRendered() {
        EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile == null) {
            return false;
        }
        String availableKeyStores = endEntityProfile.getValue(EndEntityProfile.AVAILKEYSTORE, 0);
        return availableKeyStores != null && availableKeyStores.contains(String.valueOf(SecConst.TOKEN_SOFT_PEM))
                && getSelectedKeyPairGenerationEnum() != null && KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())
                && !isApprovalRequired();
    }

    /**
     * @return true if certificate download options should be provided (e.g. a CSR was used and no approvals are required)
     */
    public boolean isGenerateFromCsrButtonRendered() {
        EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile == null) {
            return false;
        }
        String availableKeyStores = endEntityProfile.getValue(EndEntityProfile.AVAILKEYSTORE, 0);
        return availableKeyStores != null && availableKeyStores.contains(String.valueOf(EndEntityConstants.TOKEN_USERGEN))
                && getSelectedKeyPairGenerationEnum() != null && KeyPairGeneration.PROVIDED_BY_USER.equals(getSelectedKeyPairGenerationEnum())
                && !isApprovalRequired();
    }

    /**
     * @return true if the current selection will require approvals
     */
    public boolean isConfirmRequestButtonRendered() {
        return isApprovalRequired();
    }

    /**
     * @return true if approvals are required as determined by state of dependencies by checking the RA API.
     */
    private boolean isApprovalRequired() {
        try {
            return raMasterApiProxyBean.getApprovalProfileForAction(raAuthenticationBean.getAuthenticationToken(),
                    ApprovalRequestType.ADDEDITENDENTITY, getCAInfo().getCAId(),
                    getAuthorizedCertificateProfiles().get(Integer.parseInt(getSelectedCertificateProfile())).getId()) != null;
        } catch (AuthorizationDeniedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return true if when the certificate preview box should be rendered
     */
    public boolean isUpdateRequestPreviewButtonRendered() {
        return isKeyAlgorithmAvailable();
    }

    /**
     * @return true if the selection of certificate template should be rendered
     */
    public boolean isSelectRequestTemplateRendered() {
        return isSelectEndEntityProfileRendered() || isSelectCertificateProfileRendered() || isSelectCertificateAuthorityRendered() || isSelectKeyPairGenerationRendered();
    }

    /**
     * @return true if the selection of end entity profile ("certificate type") should be rendered
     */
    public boolean isSelectEndEntityProfileRendered() {
        return getAvailableEndEntityProfiles().size() > 1 ||
                (getAvailableEndEntityProfiles().size() == 1 && isRenderNonModifiableTemplates());
    }

    /**
     * @return true if the selection of certificate profile ("certificate sub-type") should be rendered
     */
    public boolean isSelectCertificateProfileRendered() {
        return StringUtils.isNotEmpty(getSelectedEndEntityProfile()) && (getAvailableCertificateProfiles().size() > 1 ||
                (getAvailableCertificateProfiles().size() == 1 && isRenderNonModifiableTemplates()));
    }

    /**
     * @return true if the description of end entity profile ("certificate type") should be rendered
     */
    public boolean isEndEntityProfileDescriptionRendered() {
        return StringUtils.isNotEmpty(getSelectedCertificateProfile());
    }

    /**
     * @return true if the description of certificate profile ("certificate sub-type") should be rendered
     */
    public boolean isCertificateProfileDescriptionRendered() {
        return StringUtils.isNotEmpty(getSelectedEndEntityProfile());
    }

    /**
     * @return true if the selection of certificate authority should be rendered
     */
    public boolean isSelectCertificateAuthorityRendered() {
        return StringUtils.isNotEmpty(getSelectedCertificateProfile()) && (getAvailableCertificateAuthorities().size() > 1 ||
                (getAvailableCertificateAuthorities().size() == 1 && isRenderNonModifiableTemplates()));
    }

    /**
     * @return true if the selection of key generation type should be rendered
     */
    public boolean isSelectKeyPairGenerationRendered() {
        return StringUtils.isNotEmpty(getSelectedCertificateAuthority()) && (getAvailableKeyPairGenerations().size() > 1 ||
                (getAvailableKeyPairGenerations().size() == 1 && isRenderNonModifiableTemplates()));
    }

    /**
     * Creates a help message whose content depends on the current value of the 'Validity'-field.
     * The help message reflects one of the following four (error) states:
     * <ul>
     * <li>The validity is empty</li>
     * <li>The validity is non-empty but cannot be parsed</li>
     * <li>The validity is a parsable date or time interval but exceeds the maximum validity specified by the certificate profile</li>
     * <li>The validity is valid (no error)</li>
     * <ul>
     */
    public String getValidityHelpMessage() {
        if (validity == null || validity.isEmpty()) {
            return raLocaleBean.getMessage("enroll_validity_help_empty");
        }
        final Date now = new Date();
        final Date validityDate = ValidityDate.getDate(validity, now, true);
        if (validityDate == null) {
            return raLocaleBean.getMessage("enroll_validity_help_unparsable");
        }
        final Date maxDate = ValidityDate.getDate(getCertificateProfile().getEncodedValidity(), now, true);
        if (validityDate.after(maxDate)) {
            return raLocaleBean.getMessage("enroll_validity_help_too_long");
        }
        // No help needed
        return StringUtils.EMPTY;
    }

    /**
     * Determines whether any non-modifiable (disabled) templates are rendered or exists as hidden.
     * If the selectable items havn't been selected yet, false is returned to not confuse a hidden item with an
     * unselected item (hence we check if an item is not rendered AND selected).
     * Hidden templates occur if there's only one selection or if the option is restricted to the profile.
     *
     * @return true if there are hidden static template options or if hidden templates are already rendered
     */
    public boolean isRenderNonModifiableTemplatesRendered() {
        if (renderNonModifiableTemplates) {
            return true;
        }
        return !isSelectEndEntityProfileRendered() ||
                (!isSelectCertificateProfileRendered() && selectedEndEntityProfile != null) ||
                (!isSelectCertificateAuthorityRendered() && selectedCertificateProfile != null) ||
                (!isSelectKeyPairGenerationRendered() && selectedCertificateAuthority != null);
    }

    /**
     * @return true if the select token drop down should be rendered
     */
    public boolean isSelectTokenRendered() {
        return KeyPairGeneration.POSTPONE.equals(getSelectedKeyPairGenerationEnum());
    }

    /**
     * @return true if the selectKeyAlgorithm should be rendered
     */
    public boolean isSelectKeyAlgorithmRendered() {
        return KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum()) && (getAvailableAlgorithmSelectItems().size() > 1 ||
                (getAvailableAlgorithmSelectItems().size() == 1 && isRenderNonModifiableTemplates()));
    }

    /**
     * @return true if the CSR upload form should be rendered
     */
    public boolean isUploadCsrRendered() {
        return getEndEntityProfile() != null && getCertificateProfile() != null && getCAInfo() != null &&
                KeyPairGeneration.PROVIDED_BY_USER.equals(getSelectedKeyPairGenerationEnum());
    }

    boolean uploadCsrDoneRendered = false;

    /**
     * @return true if the the CSR has been uploaded
     */
    public boolean isUploadCsrDoneRendered() {
        return this.uploadCsrDoneRendered;
    }

    /**
     * @return True if the option "Validity override" is enabled in the active certificate profile
     */
    public boolean isValidityOverrideEnabled() {
        return getCertificateProfile().getAllowValidityOverride();
    }

    /**
     * Determines if the custom validity date entered by the user is valid.
     * This method checks whether the following two conditions hold:
     * <ul>
     * <li>The validity can be parsed and interpreted as a date.</li>
     * <li>The validity does not exceed the validity of the certificate profile.</li>
     * <ul>
     *
     * @return True if the validity set by the user is valid
     */
    public boolean isValidityValid() {
        if (!isSetCustomValidity()) {
            // The validity of the certificate profile is used instead, and is always valid
            return true;
        }
        return getUserDefinedValidityIfSpecified() != null;
    }

    /**
     * Returns the validity string specified by the user or null if one of the
     * following conditions hold:
     * <ul>
     * <li>Validity override is disabled in the certificate profile</li>
     * <li>User defined validity is disabled in the UI</li>
     * <li>The validity cannot be parsed (invalid format)</li>
     * <li>The validity exceeds the maximum validity as specified by the certificate profile</li>
     * <ul>
     *
     * @return The validity as a string or null
     */
    private String getUserDefinedValidityIfSpecified() {
        if (!isValidityOverrideEnabled()) {
            return null;
        }
        if (!isSetCustomValidity()) {
            return null;
        }
        final Date anchorDate = new Date();
        final String validityToCheck = validity;
        final Date userDate = ValidityDate.getDate(validityToCheck, anchorDate, true);
        if (userDate == null) {
            return null;
        }
        final Date maxDate = ValidityDate.getDate(getCertificateProfile().getEncodedValidity(), anchorDate, true);
        if (userDate.after(maxDate)) {
            return null;
        }
        return validityToCheck;
    }

    /**
     * Checks if each group of fields contains non-modifiable (disabled) values. If a specific fields is empty, the check is skipped
     * and false is returned to not confuse hidden fields with empty fields.
     * Hidden fields occur if there's only one selection or if the option is restricted to the profile.
     *
     * @return true if not all fields (e.g. non-modifiable) are rendered by default or if hidden fields are already rendered.
     */
    public boolean isRenderNonModifiableFieldsRendered() {
        if (renderNonModifiableFields) {
            return true;
        }
        if (getSubjectDn() != null && !isAllFieldInstancesRendered(getSubjectDn().getFieldInstances())) {
            return true;
        }
        if (getSubjectAlternativeName() != null && !isAllFieldInstancesRendered(getSubjectAlternativeName().getFieldInstances())) {
            return true;
        }
        if (getSubjectDirectoryAttributes() != null && !isAllFieldInstancesRendered(getSubjectDirectoryAttributes().getFieldInstances())) {
            return true;
        }
        return false;
    }

    private boolean isAllFieldInstancesRendered(final Collection<FieldInstance> fieldInstances) {
        for (final FieldInstance instance : fieldInstances) {
            if (!isFieldInstanceRendered(instance)) {
                return false;
            }
        }
        return true;
    }

    public boolean isEABrendered(){
        final boolean result = (isKeyAlgorithmAvailable() || isTokenTypeAvilable()) && (getCertificateProfile() != null
                && getCertificateProfile().getEabNamespaces() != null && !getCertificateProfile().getEabNamespaces().isEmpty());
        if (result && eabConfiguration == null) {
            eabConfiguration = raMasterApiProxyBean.getGlobalConfiguration(EABConfiguration.class);
        }
        return result;
    }

    /**
     * @return the provideRequestMetadataRendered
     */
    public boolean isProvideUserCredentialsRendered() {
        return (isKeyAlgorithmAvailable() || isTokenTypeAvilable()) && (isUsernameRendered() || isPasswordRendered() || isEmailRendered());
    }

    /**
     * @return the confirmRequestRendered
     */
    public boolean isConfirmRequestRendered() {
        return isKeyAlgorithmAvailable() || isTokenTypeAvilable();
    }

    /**
     * @return the provideRequestInfoRendered
     */
    public boolean isProvideRequestInfoRendered() {
        return isKeyAlgorithmAvailable() || isTokenTypeAvilable();
    }

    /**
     * @return true if a token type has been selected
     */
    private boolean isTokenTypeAvilable() {
        return selectedTokenType > 0;
    }

    private boolean isKeyAlgorithmAvailable() {
        if (KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum()) && StringUtils.isNotEmpty(getSelectedAlgorithm())) {
            return true;
        }
        if (KeyPairGeneration.PROVIDED_BY_USER.equals(getSelectedKeyPairGenerationEnum()) && algorithmFromCsr != null) {
            return true;
        }
        return false;
    }

    //-----------------------------------------------------------------------------------------------
    //All reset* methods should be able to clear/reset states that have changed during init* methods.
    //Always make sure that reset methods are properly chained

    //Invoked by commandButton id="resetButton"
    public String reset() {
        //Invalidate view tree by redirecting to the same page
        String viewId = FacesContext.getCurrentInstance().getViewRoot().getViewId();
        return viewId + "?faces-redirect=true";
    }

    private void resetAlgorithmCsrUpload() {
        algorithmFromCsr = null;
        certificateRequest = null;
    }

    private void resetRequestInfo() {
        subjectDn = null;
        subjectAlternativeName = null;
        subjectDirectoryAttributes = null;
        algorithmFromCsr = null;
        endEntityInformation = null;
        setRequestId(0);
    }

    private void setProfileDefaults() {
        final EndEntityProfile endEntityProfile = getEndEntityProfile();
        cabfOrganizationIdentifier = endEntityProfile != null ? endEntityProfile.getCabfOrganizationIdentifier() : null;
    }

    //-----------------------------------------------------------------------------------------------
    //Action methods

    public void applyRequestTemplate() {
        // NOOP here. Validators and setters do the real work.
    }

    public void applyAlgorithm() {
        // NOOP here. Validators and setters do the real work.
    }

    private boolean renderCsrDetailedInfo = false;

    public boolean isRenderCsrDetailedInfo() {
        return renderCsrDetailedInfo;
    }

    public boolean isRenderOtherCertificateData() {
        return getEndEntityProfile().getUseExtensiondata() || getEndEntityProfile().isPsd2QcStatementUsed() || isCabfOrganizationIdentifierRendered();
    }

    public boolean isRenderCertExtensionDataField() {
        return getEndEntityProfile().getUseExtensiondata();
    }

    public boolean isRenderPsd2QcStatementField() {
        return getEndEntityProfile().isPsd2QcStatementUsed();
    }

    public void setRenderCsrDetailedInfo(boolean renderCsrDetailedInfo) {
        this.renderCsrDetailedInfo = renderCsrDetailedInfo;
    }

    public void renderCsrDetailedInfoToggle() {
        renderCsrDetailedInfo = !renderCsrDetailedInfo;
    }

    public void renderNonModifiableTemplatesToggle() {
        renderNonModifiableTemplates = !renderNonModifiableTemplates;
    }

    public void renderNonModifiableFieldsToggle() {
        renderNonModifiableFields = !renderNonModifiableFields;
    }

    public void renderRequestPreviewMoreToggle() {
        requestPreviewMoreDetails = !requestPreviewMoreDetails;
    }

    public void renderSetCustomValidityToggle() {
        setCustomValidity = !setCustomValidity;
    }

    public void uploadCsrChange() {
        algorithmFromCsr = null;
        uploadCsrDoneRendered = false;
    }

    /**
     * Populate the state of modifiable fields with the CSR that was saved during file upload validation
     */
    public void uploadCsr() {
        subjectDn = null;
        validateCsr(certificateRequest);
        //If PROVIDED BY USER key generation is selected, try fill Subject DN fields from CSR (Overwrite the fields set by previous CSR upload if any)
        if (getSelectedKeyPairGenerationEnum() != null && KeyPairGeneration.PROVIDED_BY_USER.equals(getSelectedKeyPairGenerationEnum()) && algorithmFromCsr != null) {
            final PKCS10CertificationRequest pkcs10CertificateRequest = CertTools.getCertificateRequestFromPem(getCertificateRequest());
            if (pkcs10CertificateRequest.getSubject() != null) {
                populateRequestFields(RequestFieldType.DN, pkcs10CertificateRequest.getSubject().toString(), getSubjectDn().getFieldInstances());
                getSubjectDn().update();
            }
            final Extension sanExtension = CertTools.getExtension(pkcs10CertificateRequest, Extension.subjectAlternativeName.getId());
            if (sanExtension != null) {
                populateRequestFields(RequestFieldType.AN, CertTools.getAltNameStringFromExtension(sanExtension), getSubjectAlternativeName().getFieldInstances());
                getSubjectAlternativeName().update();
            } else {
                // If an updated CSR did not have any AN, make sure we clean the fields
                this.subjectAlternativeName = null;
            }
            final Extension subjectDirectoryAttributes = CertTools.getExtension(pkcs10CertificateRequest, Extension.subjectDirectoryAttributes.getId());
            if (subjectDirectoryAttributes != null) {
                ASN1Primitive parsedValue = (ASN1Primitive) subjectDirectoryAttributes.getParsedValue();
                try {
                    final String subjectDirectoryAttributeString = SubjectDirAttrExtension.getSubjectDirectoryAttribute(parsedValue);
                    populateRequestFields(RequestFieldType.DIRATTR, subjectDirectoryAttributeString, getSubjectDirectoryAttributes().getFieldInstances());
                    getSubjectDirectoryAttributes().update();
                } catch (ParseException | IllegalArgumentException e) {
                    log.debug("Invalid Subject Directory Attributes Extension: " + e.getMessage());
                    // If an updated CSR did not have any Directory attributes, make sure we clean the fields
                    this.subjectDirectoryAttributes = null;
                }
            } else {
                // If an updated CSR did not have any Directory attributes, make sure we clean the fields
                this.subjectDirectoryAttributes = null;
            }

            uploadCsrDoneRendered = true;
        }
    }

    // enum to make type selection for populateRequestFields easy and fixed, with good toString() value for debug log
    enum RequestFieldType {
        DN,
        AN,
        DIRATTR
      }

    /**
     * Populate the fieldInstances parameter with values from the CSR when the instances are modifiable
     */
    private void populateRequestFields(final RequestFieldType type, final String subject, final Collection<FieldInstance> fieldInstances) {
        final List<String> subjectFieldsFromParsedCsr = CertTools.getX500NameComponents(subject);
        bothLoops:
        for (final String subjectField : subjectFieldsFromParsedCsr) {
            if (log.isDebugEnabled()) {
                log.debug("Parsing the subject " + type + " field '" + subjectField + "'...");
            }
            final String[] nameValue = subjectField.split("=");
            if (nameValue != null && nameValue.length == 2) {
                Integer dnId = null;
                switch (type) {
                case DN:
                    dnId = DnComponents.getDnIdFromDnName(nameValue[0]);
                    break;
                case AN:
                    dnId = DnComponents.getDnIdFromAltName(nameValue[0]);
                    break;
                case DIRATTR:
                    dnId = DnComponents.getDnIdFromDirAttr(nameValue[0]);
                    break;
                }
                if (log.isDebugEnabled()) {
                    log.debug(" dnId=" + dnId);
                }
                if (dnId != null) {
                    //In the case of multiple fields (etc. two CNs), find the first modifiable with a non-default value
                    for (final FieldInstance fieldInstance : fieldInstances) {
                        if (DnComponents.profileIdToDnId(fieldInstance.getProfileId()) == dnId.intValue()) {
                            if (fieldInstance.isModifiable()) {
                                if (log.isDebugEnabled()) {
                                    log.debug(" fieldInstance.value=" + fieldInstance.getValue() + " fieldInstance.defaultValue=" + fieldInstance.getDefaultValue());
                                }
                                if (StringUtils.isEmpty(fieldInstance.getValue()) || fieldInstance.getValue().equals(fieldInstance.getDefaultValue())) {
                                    fieldInstance.setValue(nameValue[1]);
                                    if (log.isDebugEnabled()) {
                                        log.debug("Modifiable subject field '" + subjectField + "' successfully parsed from CSR");
                                    }
                                    continue bothLoops;
                                }
                            } else if (fieldInstance.isSelectable()) {
                                if (log.isDebugEnabled()) {
                                    log.debug(" fieldInstance.value=" + fieldInstance.getValue() + " fieldInstance.defaultValue=" + fieldInstance.getDefaultValue());
                                }
                                if (fieldInstance.getSelectableValues().contains(nameValue[1])) {
                                    fieldInstance.setValue(nameValue[1]);
                                    if (log.isDebugEnabled()) {
                                        log.debug("Selectable subject field '" + subjectField + "' successfully parsed from CSR");
                                    }
                                    continue bothLoops;
                                }
                            }
                        }
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("Unparsable subject " + type + " field '" + subjectField +
                        "' from CSR, field is invalid or not a modifiable option in the end entity profile.");
            }
        }
    }

    /**
     * Proceed with request that will require approval
     */
    public void confirmRequest() {
        String username = getEndEntityInformation().getUsername();
        if (raMasterApiProxyBean.searchUser(raAuthenticationBean.getAuthenticationToken(), username) == null) {
            if (KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())) {
                addEndEntityAndGenerateP12();
            } else if (KeyPairGeneration.POSTPONE.equals(getSelectedKeyPairGenerationEnum())) {
                addEndEntity();
            } else {
                addEndEntityAndGenerateCertificateDer();
            }
        } else {
            raLocaleBean.addMessageError(ENROLL_USERNAME_ALREADY_EXISTS, username);
        }
    }

    /**
     * Calculate the summary of holders from the current state for the certificate Subjects
     */
    public void updateRequestPreview() {
        getSubjectDn().update();
        getSubjectAlternativeName().update();
        getSubjectDirectoryAttributes().update();
    }

    public void addEndEntity() {
        addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_USERGEN, null);
    }

    public void addEndEntityAndGenerateCertificateDer() {
        byte[] token = addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_USERGEN, TokenDownloadType.DER);
        downloadToken(token, APPLICATION_OCTET_STREAM, ".der");
    }

    public void addEndEntityAndGenerateCertificatePkcs7() {
        byte[] token = addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_USERGEN, TokenDownloadType.PKCS7);
        downloadToken(token, APPLICATION_OCTET_STREAM, ".p7b");
    }

    public void addEndEntityAndGenerateCertificatePemFullChain() {
        byte[] token = addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_USERGEN, TokenDownloadType.PEM_FULL_CHAIN);
        downloadToken(token, APPLICATION_OCTET_STREAM, ".pem");
    }

    public void addEndEntityAndGenerateCertificatePem() {
        byte[] token = addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_USERGEN, TokenDownloadType.PEM);
        downloadToken(token, APPLICATION_OCTET_STREAM, ".pem");
    }

    public void addEndEntityAndGenerateP12() {
        byte[] token = addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_SOFT_P12, null);
        downloadToken(token, APPLICATION_X_PKCS12, ".p12");
    }

    public void addEndEntityAndgenerateBcfks() {
        byte[] token = addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_SOFT_BCFKS, null);
        downloadToken(token, APPLICATION_X_PKCS12, ".p12");
    }

    public void addEndEntityAndGenerateJks() {
        byte[] token = addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_SOFT_JKS, null);
        downloadToken(token, APPLICATION_OCTET_STREAM, ".jks");
    }

    public void addEndEntityAndGeneratePem() {
        byte[] token = addEndEntityAndGenerateToken(EndEntityConstants.TOKEN_SOFT_PEM, null);
        downloadToken(token, APPLICATION_OCTET_STREAM, ".pem");
    }

    private ExtendedInformation getProcessedExtendedInformation() {
        final ExtendedInformation extendedInformation = new ExtendedInformation();
        final Properties properties = new Properties();
        
        extendedInformation.setMaxLoginAttempts(getEndEntityProfile().getMaxFailedLogins());
        extendedInformation.setRemainingLoginAttempts(getEndEntityProfile().getMaxFailedLogins());
        
        if (getUserDefinedValidityIfSpecified() != null) {
            extendedInformation.setCertificateEndTime(getUserDefinedValidityIfSpecified());
        }
        
        if (getSubjectDirectoryAttributes() != null) {
            extendedInformation.setSubjectDirectoryAttributes(getSubjectDirectoryAttributes().toString());
        }

        // Add extension data
        if (extensionData != null) {
            try {
                properties.load(new StringReader(extensionData));
            } catch (IOException ex) {
                // Should not happen as we are only reading from a String.
                throw new RuntimeException(ex);
            }
            for (Object o : properties.keySet()) {
                if (o instanceof String) {
                    String key = (String) o;
                    if (OID.isStartingWithValidOID(key)) {
                        extendedInformation.setExtensionData(key, properties.getProperty(key));
                    }
                }
            }
        }
        // Add account binding Id
        if (accountBindingId != null) {
            extendedInformation.setAccountBindingId(accountBindingId);
        }
        // Add PSD2 QC Statement
        if (selectedPsd2PspRoles != null && !selectedPsd2PspRoles.isEmpty()) {
            final List<PSD2RoleOfPSPStatement> psd2RoleOfPSPStatements = new ArrayList<>();
            for (String role : selectedPsd2PspRoles) {
                psd2RoleOfPSPStatements.add(new PSD2RoleOfPSPStatement(QcStatement.getPsd2Oid(role), role));
            }
            extendedInformation.setQCEtsiPSD2NcaName(psd2NcaName);
            extendedInformation.setQCEtsiPSD2NcaId(psd2NcaId);
            extendedInformation.setQCEtsiPSD2RolesOfPSP(psd2RoleOfPSPStatements);
        }
        extendedInformation.setCabfOrganizationIdentifier(cabfOrganizationIdentifier);

        return extendedInformation;
    }
    
    /**
     * Adds end entity and creates its token that will be downloaded. This method is responsible for deleting the end entity if something goes wrong with token creation.
     *
     * @param tokenType         the type of the token that will be created (one of: TOKEN_USERGEN, TOKEN_SOFT_P12, TOKEN_SOFT_JKS from EndEntityConstants)
     * @param tokenDownloadType the download type/format of the token. This is used only with TOKEN_USERGEN since this is the only one that have different formats: PEM, DER,...)
     * @return generated token as byte array or null if token could not be generated
     */
    private byte[] addEndEntityAndGenerateToken(int tokenType, TokenDownloadType tokenDownloadType) {
        //Update the EndEntityInformation data
        getSubjectDn().update();
        getSubjectAlternativeName().update();
        getSubjectDirectoryAttributes().update();

        //Fill End Entity information
        final EndEntityInformation endEntityInformation = getEndEntityInformation();

        endEntityInformation.setCAId(getCAInfo().getCAId());
        endEntityInformation.setCardNumber(""); //TODO Card Number
        endEntityInformation.setCertificateProfileId(authorizedCertificateProfiles.get(Integer.parseInt(getSelectedCertificateProfile())).getId());
        endEntityInformation.setDN(getSubjectDn().toString());
        endEntityInformation.setEndEntityProfileId(authorizedEndEntityProfiles.get(Integer.parseInt(getSelectedEndEntityProfile())).getId());
        endEntityInformation.setExtendedInformation(getProcessedExtendedInformation());
        endEntityInformation.setStatus(EndEntityConstants.STATUS_NEW);
        endEntityInformation.setSubjectAltName(getSubjectAlternativeName().toString());
        endEntityInformation.setTimeCreated(new Date());
        endEntityInformation.setTimeModified(new Date());
        endEntityInformation.setType(new EndEntityType(EndEntityTypes.ENDUSER));
        // sendnotification, keyrecoverable and print must be set after setType, because it adds to the type
        endEntityInformation.setSendNotification(getEndEntityProfile().isSendNotificationUsed() && getEndEntityProfile().isSendNotificationDefault() && !endEntityInformation.getSendNotification());
        endEntityInformation.setKeyRecoverable(getEndEntityProfile().isKeyRecoverableUsed() && getEndEntityProfile().isKeyRecoverableDefault() && !endEntityInformation.getKeyRecoverable());
        endEntityInformation.setPrintUserData(false); // TODO not sure...
        endEntityInformation.setTokenType(tokenType);

        // Fill end-entity information (Username and Password)
        final byte[] randomData = new byte[16];
        final Random random = new SecureRandom();
        random.nextBytes(randomData);
        if (StringUtils.isBlank(endEntityInformation.getUsername())) {
            String autousername = new String(Hex.encode(randomData));
            while (raMasterApiProxyBean.searchUser(raAuthenticationBean.getAuthenticationToken(), autousername) != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Autogenerated username '" + autousername + "' is already reserved. Generating the new one...");
                }
                random.nextBytes(randomData);
                autousername = new String(Hex.encode(randomData));
            }
            if (log.isDebugEnabled()) {
                log.debug("Unique username '" + autousername + "' has been generated");
            }
            endEntityInformation.setUsername(autousername);
        }
        if (getEndEntityProfile().useAutoGeneratedPasswd()) {
            // If auto-generated passwords are used, this is set on the CA side when adding or changing the EE as long as the password is null
            endEntityInformation.setPassword(null);
        } else if (StringUtils.isEmpty(endEntityInformation.getPassword())) {
            // If not needed just use some random data
            random.nextBytes(randomData);
            endEntityInformation.setPassword(new String(Hex.encode(CertTools.generateSHA256Fingerprint(randomData))));
        }

        //Fill end-entity information (KeyStoreAlgorithm* or CertificateRequest)
        if (KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())) {
            final String[] tokenKeySpecSplit = getSelectedAlgorithm().split("_");
            endEntityInformation.getExtendedInformation().setKeyStoreAlgorithmType(tokenKeySpecSplit[0]);
            if (tokenKeySpecSplit.length > 1) {
                endEntityInformation.getExtendedInformation().setKeyStoreAlgorithmSubType(tokenKeySpecSplit[1]);
            }
        } else if (KeyPairGeneration.PROVIDED_BY_USER.equals(getSelectedKeyPairGenerationEnum())) {
            try {
                endEntityInformation.getExtendedInformation().setCertificateRequest(CertTools.getCertificateRequestFromPem(getCertificateRequest()).getEncoded());
            } catch (IOException e) {
                raLocaleBean.addMessageError(ENROLL_INVALID_CERTIFICATE_REQUEST);
                return null;
            }
        }

        ErrorCode errorCode = null; // we need to be able to check for USER_ALREADY_EXISTS error during cleanup
        byte[] ret = null;
        try {
            // Add end-entity
            // Generates a keystore token if user has specified "ON SERVER" key pair generation.
            // Generates a certificate token if user has specified "PROVIDED_BY_USER" key pair generation
            if (KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())) {
                ret = raMasterApiProxyBean.addUserAndGenerateKeyStore(raAuthenticationBean.getAuthenticationToken(), endEntityInformation, false);
                if (ret == null) {
                    raLocaleBean.addMessageError("enroll_certificate_could_not_be_generated", endEntityInformation.getUsername(), "Check server log");
                    log.info("Certificate could not be generated for end entity with username " + endEntityInformation.getUsername());
                }
            } else if (KeyPairGeneration.PROVIDED_BY_USER.equals(getSelectedKeyPairGenerationEnum())) {
                endEntityInformation.getExtendedInformation().setCertificateRequest(CertTools.getCertificateRequestFromPem(getCertificateRequest()).getEncoded());
                final byte[] certificateDataToDownload = raMasterApiProxyBean.addUserAndCreateCertificate(raAuthenticationBean.getAuthenticationToken(),
                        endEntityInformation, false);
                if (certificateDataToDownload == null) {
                    raLocaleBean.addMessageError("enroll_certificate_could_not_be_generated", endEntityInformation.getUsername(), "Check server log");
                    log.info("Certificate could not be generated for end entity with username " + endEntityInformation.getUsername());
                } else if (tokenDownloadType == TokenDownloadType.PEM_FULL_CHAIN) {
                    X509Certificate certificate = CertTools.getCertfromByteArray(certificateDataToDownload, X509Certificate.class);
                    LinkedList<Certificate> chain = new LinkedList<>(getCAInfo().getCertificateChain());
                    chain.addFirst(certificate);
                    ret = CertTools.getPemFromCertificateChain(chain);
                } else if (tokenDownloadType == TokenDownloadType.PKCS7) {
                    X509Certificate certificate = CertTools.getCertfromByteArray(certificateDataToDownload, X509Certificate.class);
                    LinkedList<Certificate> chain = new LinkedList<>(getCAInfo().getCertificateChain());
                    chain.addFirst(certificate);
                    ret = CertTools.getPemFromPkcs7(CertTools.createCertsOnlyCMS(CertTools.convertCertificateChainToX509Chain(chain)));
                } else if (tokenDownloadType == TokenDownloadType.PEM) {
                    X509Certificate certificate = CertTools.getCertfromByteArray(certificateDataToDownload, X509Certificate.class);
                    ret = CertTools.getPemFromCertificateChain(Arrays.asList((Certificate) certificate));
                } else {
                    ret = certificateDataToDownload;
                }
            } else if (KeyPairGeneration.POSTPONE.equals(getSelectedKeyPairGenerationEnum())) {
                endEntityInformation.setTokenType(selectedTokenType);
                raMasterApiProxyBean.addUser(raAuthenticationBean.getAuthenticationToken(), endEntityInformation, false);
                if (!isRequestIdInfoRendered()) {
                    raLocaleBean.addMessageInfo("enroll_end_entity_has_been_successfully_added", endEntityInformation.getUsername());
                }
            } else {
                throw new IllegalStateException("No key pair generation option selected");
            }
        } catch (AuthorizationDeniedException e) {
            raLocaleBean.addMessageInfo("enroll_unauthorized_operation", e.getMessage());
            log.info(raAuthenticationBean.getAuthenticationToken() + " is not authorized to execute this operation", e);
        } catch (WaitingForApprovalException e) {
            requestId = e.getRequestId();
            log.info("Request with ID " + requestId + " is still waiting for approval");
        } catch (EjbcaException e) {
            errorCode = EjbcaException.getErrorCode(e);
            if (errorCode != null) {
                if (errorCode.equals(ErrorCode.USER_ALREADY_EXISTS)) {
                    raLocaleBean.addMessageError(ENROLL_USERNAME_ALREADY_EXISTS, endEntityInformation.getUsername());
                    log.info("Client " + raAuthenticationBean.getAuthenticationToken() + " failed to add end entity since the username " + endEntityInformation.getUsername() + " already exists");
                } else if (errorCode.equals(ErrorCode.CERTIFICATE_WITH_THIS_SUBJECTDN_ALREADY_EXISTS_FOR_ANOTHER_USER)) {
                    raLocaleBean.addMessageError("enroll_subject_dn_already_exists_for_another_user", subjectDn.getValue());
                    log.info("Subject DN " + subjectDn.getValue() + " already exists for another user", e);
                } else if (errorCode.equals(ErrorCode.USER_DOESNT_FULFILL_END_ENTITY_PROFILE)) {
                    raLocaleBean.addMessageError("enroll_user_does_not_fulfill_profile", cleanExceptionMessage(e));
                    log.info("End entity information does not fulfill profile: " + e.getMessage() + ", " + errorCode);
                } else {
                    reportGenericError(errorCode, e);
                }
            } else {
                reportGenericError(null, e);
            }
        } catch (Exception e) {
            reportGenericError(null, e);
        } finally {
            cleanUpEndEntities(errorCode, ret);
            endEntityInformation.setUsername(StringUtils.EMPTY);
        }
        return ret;
    }

    /**
     * Reports an error message in the GUI and in the server log.
     * @param errorCode Error code, may be null
     * @param exception Exception
     */
    private void reportGenericError(final ErrorCode errorCode, final Exception exception) {
        switch (getSelectedKeyPairGenerationEnum()) {
        case ON_SERVER:
            reportGenericError("enroll_keystore_could_not_be_generated",
                    "Keystore could not be generated for user " + getEndEntityInformation().getUsername(),
                    errorCode, exception);
            return;
        case POSTPONE:
            reportGenericError("enroll_end_entity_could_not_be_added",
                    "End entity " + getEndEntityInformation().getUsername() + " could not be added",
                    errorCode, exception);
            return;
        case PROVIDED_BY_USER:
            reportGenericError("enroll_certificate_could_not_be_generated",
                    "Certificate could not be generated for end entity with username " + getEndEntityInformation().getUsername(),
                    errorCode, exception);
            return;
        }
        throw new IllegalStateException("Unhandled key pair generation option");
    }

    /**
     * Reports an error message in the GUI and in the server log.
     * @param messageKey Language string key
     * @param logMessage Message to log at INFO level
     * @param errorCode Error code, may be null
     * @param exception Exception
     */
    private void reportGenericError(final String messageKey, final String logMessage, final ErrorCode errorCode, final Exception exception) {
        if (errorCode != null) {
            raLocaleBean.addMessageError(messageKey, getEndEntityInformation().getUsername(), raLocaleBean.getErrorCodeMessage(errorCode));
        } else {
            raLocaleBean.addMessageError(messageKey, getEndEntityInformation().getUsername(), exception.getMessage());
        }
        final StringBuilder sb = new StringBuilder(logMessage);
        sb.append(": ");
        sb.append(exception.getMessage());
        if (errorCode != null) {
            sb.append(", ");
            sb.append(errorCode);
        }
        log.info(sb.toString(), exception);
    }

    /** Removes exception name so it's not shown to the user. */
    private String cleanExceptionMessage(final Throwable e) {
        String message = ExceptionUtils.getRootCauseMessage(e);
        if (message != null) {
            message = message.replaceFirst("^[^:]*EndEntityProfileValidationException: ", "");
        }
        return message;
    }

    /**
     * End entity clean-up must be done if enrollment could not be completed (but end-entity has been added and wasn't already existing)
     */
    private void cleanUpEndEntities(final ErrorCode errorCode, final byte[] certificate) {
        if (certificate == null) {
            if ((errorCode == null || !errorCode.equals(ErrorCode.USER_ALREADY_EXISTS))
                    && !KeyPairGeneration.POSTPONE.equals(getSelectedKeyPairGenerationEnum())) {
                EndEntityInformation endEntityInfoFromCA = raMasterApiProxyBean.searchUser(raAuthenticationBean.getAuthenticationToken(),
                        endEntityInformation.getUsername());
                try {
                    if (endEntityInfoFromCA != null && endEntityInfoFromCA.getStatus() != EndEntityConstants.STATUS_GENERATED) {
                        raMasterApiProxyBean.deleteUser(raAuthenticationBean.getAuthenticationToken(), endEntityInformation.getUsername());
                    }
                } catch (AuthorizationDeniedException e) {
                    throw new IllegalStateException(e);
                }
            }
        } else {
            return; // We simply return since certificate is generated and EE must not be deleted.
        }
    }

    /**
     * Send a file to the client if token parameter is not set to null
     */
    private void downloadToken(byte[] token, String responseContentType, String fileExtension) {
        if (token == null) {
            return;
        }
        //Download the token
        FacesContext fc = FacesContext.getCurrentInstance();
        ExternalContext ec = fc.getExternalContext();
        ec.responseReset(); // Some JSF component library or some Filter might have set some headers in the buffer beforehand. We want to get rid of them, else it may collide.
        ec.setResponseContentType(responseContentType);
        ec.setResponseContentLength(token.length);
        final String fileName = getFileName();
        ec.setResponseHeader("Content-Disposition",
                "attachment; filename=\"" + fileName + fileExtension + "\""); // The Save As popup magic is done here. You can give it any file name you want, this only won't work in MSIE, it will use current request URL as file name instead.
        try (final OutputStream output = ec.getResponseOutputStream()) {
            output.write(token);
            output.flush();
            fc.responseComplete(); // Important! Otherwise JSF will attempt to render the response which obviously will fail since it's already written with a file and closed.
        } catch (IOException e) {
            raLocaleBean.addMessageError(raLocaleBean.getMessage("enroll_token_could_not_be_downloaded", fileName), e);
            log.info("Token " + fileName + " could not be downloaded", e);
        }
    }

    /**
     * Calculates the filename for a token (P12 or PEM file) sent back to the client based on
     * the common name of the certificate.
     *
     * @return the file name to use in the content disposition header, filename safe characters
     */
    private String getFileName() {
        final String commonName = CertTools.getPartFromDN(getEndEntityInformation().getDN(), "CN");
        if (StringUtils.isEmpty(commonName)) {
            return "certificatetoken";
        }
        if (StringUtils.isAsciiPrintable(commonName)) {
            return StringTools.stripFilename(commonName);
        }
        return Base64.encodeBase64String(commonName.getBytes());
    }

    /**
     * Update RFC822NAME with value from end entity email
     */
    public void updateRfcAltName() {
        EndEntityProfile.FieldInstance rfc822Name = subjectAlternativeName.getFieldInstancesMap().get(DnComponents.RFC822NAME).get(0);
        if (rfc822Name != null) {
            if (rfc822Name.getRfcEmailUsed()) {
                String email = getEndEntityInformation().getEmail();
                if (email != null) {
                    rfc822Name.setValue(email);
                    return;
                }
            }
            rfc822Name.setValue("");
        }
    }

    //-----------------------------------------------------------------------------------------------
    //Validators

    /**
     * Check if the currently set username exists and that the state of subject DN is valid via 2 calls to the RA API.
     */
    public final void checkRequestPreview() {
        checkUserCredentials();
        checkSubjectDn();
    }

    /**
     * Check if the currently set username exists via the RA API and render an error message if it does.
     */
    public final void checkUserCredentials() {
        final String username = getEndEntityInformation().getUsername();
        if (username != null && !username.isEmpty() && raMasterApiProxyBean.searchUser(raAuthenticationBean.getAuthenticationToken(), username) != null) {
            FacesContext.getCurrentInstance().addMessage(userCredentialsMessagesComponent.getClientId(), new FacesMessage(FacesMessage.SEVERITY_WARN,
                    raLocaleBean.getMessage(ENROLL_USERNAME_ALREADY_EXISTS, username), null));
        }
    }

    /**
     * Update the current state of the EE-holder and validate the subject DN via the RA API.
     */
    public final void checkSubjectDn() {
        try {
            final EndEntityInformation endEntityInformation = getEndEntityInformation();
            endEntityInformation.setCAId(getCAInfo().getCAId());
            if (log.isDebugEnabled()) {
                log.debug("checkSubjectDn: '" + subjectDn.getUpdatedValue() + "'");
            }
            endEntityInformation.setDN(subjectDn.getUpdatedValue());
            raMasterApiProxyBean.checkSubjectDn(raAuthenticationBean.getAuthenticationToken(), endEntityInformation);
        } catch (AuthorizationDeniedException e) {
            log.error(e);
        } catch (EjbcaException e) {
            if (ErrorCode.CERTIFICATE_WITH_THIS_SUBJECTDN_ALREADY_EXISTS_FOR_ANOTHER_USER.equals(e.getErrorCode())) {
                FacesContext.getCurrentInstance().addMessage(subjectDnMessagesComponent.getClientId(), new FacesMessage(FacesMessage.SEVERITY_WARN,
                        raLocaleBean.getMessage("enroll_certificate_with_subject_dn_already_exists", subjectDn.getValue()), null));
            } else {
                FacesContext.getCurrentInstance().addMessage(subjectDnMessagesComponent.getClientId(),
                        new FacesMessage(FacesMessage.SEVERITY_WARN, raLocaleBean.getErrorCodeMessage(e.getErrorCode()), null));
            }
        }
    }

    /**
     * Validate that password and password confirm entries match and render error messages otherwise.
     */
    public final void validatePassword(ComponentSystemEvent event) {
        if (isPasswordRendered()) {
            FacesContext fc = FacesContext.getCurrentInstance();
            UIComponent components = event.getComponent();
            UIInput uiInputPassword = (UIInput) components.findComponent("passwordField");
            String password = uiInputPassword.getLocalValue() == null ? "" : uiInputPassword.getLocalValue().toString();
            UIInput uiInputConfirmPassword = (UIInput) components.findComponent("passwordConfirmField");
            String confirmPassword = uiInputConfirmPassword.getLocalValue() == null ? "" : uiInputConfirmPassword.getLocalValue().toString();
            if (password.isEmpty()) {
                fc.addMessage(confirmPasswordComponent.getClientId(fc), raLocaleBean.getFacesMessage("enroll_password_can_not_be_empty"));
                fc.renderResponse();
            }
            if (!password.equals(confirmPassword)) {
                fc.addMessage(confirmPasswordComponent.getClientId(fc), raLocaleBean.getFacesMessage("enroll_passwords_are_not_equal"));
                fc.renderResponse();
            }
        }
    }

    public void actionUpdateCsrInfoFields() {
        String fileName = uploadFile.getName();

        csrFileName = fileName;

        String fileContents;
        try {
            fileContents = new String(uploadFile.getBytes());
        } catch (IOException e) {
            raLocaleBean.addMessageError(ENROLL_INVALID_CERTIFICATE_REQUEST);
            throw new ValidatorException(new FacesMessage(raLocaleBean.getMessage(ENROLL_INVALID_CERTIFICATE_REQUEST)));
        }

        validateCsr(fileContents);
        if (algorithmFromCsr != null) { // valid CSR
            uploadCsr();
        }
    }

    /**
     * Validate an uploaded CSR and store the extracted key algorithm and CSR for later use.
     */
    public final void validateCsr(String csrValue) throws ValidatorException {
        algorithmFromCsr = null;
        if (csrValue != null && csrValue.length() > EnrollMakeNewRequestBean.MAX_CSR_LENGTH) {
            log.info("CSR uploaded was too large: " + csrValue.length());
            raLocaleBean.addMessageError(ENROLL_INVALID_CERTIFICATE_REQUEST);
            throw new ValidatorException(new FacesMessage(raLocaleBean.getMessage(ENROLL_INVALID_CERTIFICATE_REQUEST)));
        }
        PKCS10CertificationRequest pkcs10CertificateRequest = CertTools.getCertificateRequestFromPem(csrValue);
        if (pkcs10CertificateRequest == null) {
            raLocaleBean.addMessageError(ENROLL_INVALID_CERTIFICATE_REQUEST);
            throw new ValidatorException(new FacesMessage(raLocaleBean.getMessage(ENROLL_INVALID_CERTIFICATE_REQUEST)));
        }

        //Get public key algorithm from CSR and check if it's allowed in certificate profile
        final JcaPKCS10CertificationRequest jcaPKCS10CertificationRequest = new JcaPKCS10CertificationRequest(pkcs10CertificateRequest);
        try {
            final String keySpecification = AlgorithmTools.getKeySpecification(jcaPKCS10CertificationRequest.getPublicKey());
            final String keyAlgorithm = AlgorithmTools.getKeyAlgorithm(jcaPKCS10CertificationRequest.getPublicKey());

            final CertificateProfile certificateProfile = getCertificateProfile();
            if (!certificateProfile.isKeyTypeAllowed(keyAlgorithm, keySpecification)) {
                raLocaleBean.addMessageError("enroll_key_algorithm_is_not_available", keyAlgorithm + "_" + keySpecification);
                throw new ValidatorException(new FacesMessage(raLocaleBean.getMessage("enroll_key_algorithm_is_not_available", keyAlgorithm + "_" + keySpecification)));
            }
            algorithmFromCsr = keyAlgorithm + " " + keySpecification;// Save for later use

            certificateRequest = csrValue;

            PublicKey publicKey = jcaPKCS10CertificationRequest.getPublicKey();
            publicKeyModulus = KeyTools.getKeyModulus(publicKey);

            publicKeyExponent = KeyTools.getKeyPublicExponent(publicKey);
            sha256Fingerprint = KeyTools.getSha256Fingerprint(certificateRequest);
            signature = KeyTools.getCertificateRequestSignature(jcaPKCS10CertificationRequest);

        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            raLocaleBean.addMessageError("enroll_unknown_key_algorithm");
            throw new ValidatorException(new FacesMessage(raLocaleBean.getMessage("enroll_unknown_key_algorithm")));
        }
    }


    //-----------------------------------------------------------------------------------------------
    // Getters and setters

    /**
     * @return the authorizedEndEntityProfiles
     */
    public IdNameHashMap<EndEntityProfile> getAuthorizedEndEntityProfiles() {
        return authorizedEndEntityProfiles;
    }

    /**
     * @return true if fields that the client can't modify should still be rendered
     */
    public boolean isRenderNonModifiableTemplates() {
        return renderNonModifiableTemplates;
    }

    public void setRenderNonModifiableTemplates(final boolean renderNonModifiableTemplates) {
        this.renderNonModifiableTemplates = renderNonModifiableTemplates;
    }

    /**
     * @return true if fields that the client can't modify should still be rendered
     */
    public boolean isRenderNonModifiableFields() {
        return renderNonModifiableFields;
    }

    public void setRenderNonModifiableFields(final boolean renderNonModifiableFields) {
        this.renderNonModifiableFields = renderNonModifiableFields;
    }

    /**
     * @return the current EndEntityProfile as determined by state of dependencies
     */
    public EndEntityProfile getEndEntityProfile() {
        if (getSelectedEndEntityProfile() != null) {
            final KeyToValueHolder<EndEntityProfile> temp = authorizedEndEntityProfiles.get(Integer.parseInt(getSelectedEndEntityProfile()));
            if (temp != null) {
                return temp.getValue();
            }
        }
        return null;
    }

    /**
     * @return The user-defined validity for the private key.
     */
    public String getValidity() {
        return validity;
    }

    /**
     * Set a user-defined validity for the private key.
     */
    public void setValidity(final String validity) {
        this.validity = validity;
    }

    /**
     * @return the current CertificateProfile as determined by state of dependencies
     */
    public CertificateProfile getCertificateProfile() {
        if (getSelectedCertificateProfile() != null) {
            KeyToValueHolder<CertificateProfile> temp = authorizedCertificateProfiles.get(Integer.parseInt(getSelectedCertificateProfile()));
            if (temp != null) {
                return temp.getValue();
            }
        }
        return null;
    }

    /**
     * @return the current CAInfo as determined by state of dependencies
     */
    private CAInfo getCAInfo() {
        if (getSelectedCertificateAuthority() != null) {
            KeyToValueHolder<CAInfo> temp = authorizedCAInfos.get(Integer.parseInt(getSelectedCertificateAuthority()));
            if (temp != null) {
                return temp.getValue();
            }
        }
        return null;
    }

    /**
     * @return the current key algorithm as determined by state of dependencies
     */
    public String getAlgorithm() {
        if (KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())) {
            return getSelectedAlgorithm();
        } else {
            return algorithmFromCsr;
        }
    }

    /**
     * @return the current lazy initialized selectedEndEntityProfile
     */
    public String getSelectedEndEntityProfile() {
        final List<Integer> availableEndEntityProfiles = getAvailableEndEntityProfiles();
        if (availableEndEntityProfiles.size() == 1) {
            setSelectedEndEntityProfile(String.valueOf(availableEndEntityProfiles.get(0)));
        }
        if (StringUtils.isNotEmpty(selectedEndEntityProfile)) {
            if (!availableEndEntityProfiles.contains(Integer.parseInt(selectedEndEntityProfile))) {
                setSelectedEndEntityProfile(null);
            }
        }
        return selectedEndEntityProfile;
    }

    /**
     * @param selectedEndEntityProfile the selectedEndEntityProfile to set
     */
    public void setSelectedEndEntityProfile(final String selectedEndEntityProfile) {
        if (!StringUtils.equals(selectedEndEntityProfile, this.selectedEndEntityProfile)) {
            this.selectedEndEntityProfile = selectedEndEntityProfile;
            // When ever the end entity profile changes this affects available request fields
            resetRequestInfo();
            setProfileDefaults();
        }
    }

    /**
     * @return the current key generation type as determined by state of dependencies as String
     */
    public String getSelectedKeyPairGeneration() {
        return getSelectedKeyPairGenerationEnum() == null ? null : getSelectedKeyPairGenerationEnum().name();
    }

    /**
     * @return the current key generation type as determined by state of dependencies as enum
     */
    private KeyPairGeneration getSelectedKeyPairGenerationEnum() {
        if (getAvailableKeyPairGenerationSelectItems().size() == 1) {
            setSelectedKeyPairGeneration(getAvailableKeyPairGenerations().get(0).name());
        }
        return selectedKeyPairGeneration;
    }

    /**
     * @param selectedKeyStoreGeneration the selectedKeyPairGeneration to set
     */
    public void setSelectedKeyPairGeneration(final String selectedKeyStoreGeneration) {
        final String currentSelection = this.selectedKeyPairGeneration == null ? null : this.selectedKeyPairGeneration.name();
        if (!StringUtils.equals(selectedKeyStoreGeneration, currentSelection)) {
            resetAlgorithmCsrUpload();
        }
        if (StringUtils.isNotEmpty(selectedKeyStoreGeneration)) {
            this.selectedKeyPairGeneration = KeyPairGeneration.valueOf(selectedKeyStoreGeneration);
        } else {
            this.selectedKeyPairGeneration = null;
        }
    }

    /**
     * @return the current available key generation types as determined by state of dependencies for UI rendering
     */
    public List<SelectItem> getAvailableKeyPairGenerationSelectItems() {
        final List<SelectItem> ret = new ArrayList<>();
        for (final KeyPairGeneration keyPairGeneration : getAvailableKeyPairGenerations()) {
            final String label = raLocaleBean.getMessage("enroll_key_pair_generation_" + keyPairGeneration.name().toLowerCase());
            ret.add(new SelectItem(keyPairGeneration.name(), label));
        }
        return ret;
    }

    /**
     * @return the current available key generation types as determined by state of dependencies
     */
    private List<KeyPairGeneration> getAvailableKeyPairGenerations() {
        final List<KeyPairGeneration> ret = new ArrayList<>();
        final EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile != null) {
            final String availableKeyStores = endEntityProfile.getValue(EndEntityProfile.AVAILKEYSTORE, 0);
            if (availableKeyStores.contains(String.valueOf(SecConst.TOKEN_SOFT_P12))
                    || availableKeyStores.contains(String.valueOf(SecConst.TOKEN_SOFT_JKS))
                    || availableKeyStores.contains(String.valueOf(SecConst.TOKEN_SOFT_PEM))) {
                ret.add(KeyPairGeneration.ON_SERVER);
            }
            if (availableKeyStores.contains(String.valueOf(SecConst.TOKEN_SOFT_BROWSERGEN))) {
                ret.add(KeyPairGeneration.PROVIDED_BY_USER);
            }
            ret.add(KeyPairGeneration.POSTPONE);
        }
        return ret;
    }

    /**
     * @return a List of available end entity profiles for UI rendering
     */
    public List<SelectItem> getAvailableEndEntityProfileSelectItems() {
        final List<SelectItem> ret = new ArrayList<>();
        for (final Integer id : getAvailableEndEntityProfiles()) {
            ret.add(new SelectItem(String.valueOf(id), authorizedEndEntityProfiles.get(id).getName()));
        }
        if (ret.size() > 1 && StringUtils.isEmpty(getSelectedEndEntityProfile())) {
            ret.add(new SelectItem(null, raLocaleBean.getMessage("enroll_select_eep_nochoice"), raLocaleBean.getMessage("enroll_select_eep_nochoice"), true));
        }
        EnrollMakeNewRequestBean.sortSelectItemsByLabel(ret);
        return ret;
    }

    /**
     * @return a List of available end entity profile identifiers
     */
    private List<Integer> getAvailableEndEntityProfiles() {
        return new ArrayList<>(authorizedEndEntityProfiles.idKeySet());
    }

    /**
     * @return the current List of available certificate profiles as determined by state of dependencies for UI rendering
     */
    public List<SelectItem> getAvailableCertificateProfileSelectItems() {
        final List<SelectItem> ret = new ArrayList<>();
        final String defaultId = getEndEntityProfile().getValue(EndEntityProfile.DEFAULTCERTPROFILE, 0);
        for (final Integer id : getAvailableCertificateProfiles()) {
            if (defaultId.equals(String.valueOf(id))) {
                // TODO: Localize
                ret.add(new SelectItem(String.valueOf(id), authorizedCertificateProfiles.get(id).getName() + " (default)"));
            } else {
                ret.add(new SelectItem(String.valueOf(id), authorizedCertificateProfiles.get(id).getName()));
            }
        }
        if (ret.size() > 1 && StringUtils.isEmpty(getSelectedCertificateProfile())) {
            ret.add(new SelectItem(null, raLocaleBean.getMessage("enroll_select_cp_nochoice"), raLocaleBean.getMessage("enroll_select_cp_nochoice"), true));
        }
        EnrollMakeNewRequestBean.sortSelectItemsByLabel(ret);
        return ret;
    }

    /**
     * @return the current List of available certificate profile identifiers as determined by state of dependencies
     */
    private List<Integer> getAvailableCertificateProfiles() {
        final List<Integer> ret = new ArrayList<>();
        final EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile != null) {
            final String[] availableCertificateProfileIds = endEntityProfile.getValue(EndEntityProfile.AVAILCERTPROFILES, 0).split(EndEntityProfile.SPLITCHAR);
            for (final String availableId : availableCertificateProfileIds) {
                final Integer id = Integer.parseInt(availableId);
                if (authorizedCertificateProfiles.containsKey(id)) {
                    ret.add(id);
                }
            }
        }
        return ret;
    }

    /**
     * @return the current List of available CAs as determined by state of dependencies
     */
    public List<SelectItem> getAvailableCertificateAuthoritySelectItems() {
        final List<SelectItem> ret = new ArrayList<>();
        final String defaultId = getEndEntityProfile().getValue(EndEntityProfile.DEFAULTCA, 0);
        for (final Integer id : getAvailableCertificateAuthorities()) {
            if (defaultId.equals(String.valueOf(id))) {
                // TODO: Localize
                ret.add(new SelectItem(String.valueOf(id), authorizedCAInfos.get(id).getName() + " (default)"));
            } else {
                ret.add(new SelectItem(String.valueOf(id), authorizedCAInfos.get(id).getName()));
            }
        }
        if (ret.size() > 1 && StringUtils.isEmpty(getSelectedCertificateAuthority())) {
            ret.add(new SelectItem(null, raLocaleBean.getMessage("enroll_select_ca_nochoice"), raLocaleBean.getMessage("enroll_select_ca_nochoice"), true));
        }
        EnrollMakeNewRequestBean.sortSelectItemsByLabel(ret);
        return ret;
    }

    public List<SelectItem> getAvailablePsd2PspRoles() {
        final List<SelectItem> pspRoles = new ArrayList<>();
        pspRoles.add(new SelectItem("PSP_AS", raLocaleBean.getMessage("enroll_psd2_psp_as")));
        pspRoles.add(new SelectItem("PSP_PI", raLocaleBean.getMessage("enroll_psd2_psp_pi")));
        pspRoles.add(new SelectItem("PSP_AI", raLocaleBean.getMessage("enroll_psd2_psp_ai")));
        pspRoles.add(new SelectItem("PSP_IC", raLocaleBean.getMessage("enroll_psd2_psp_ic")));
        return pspRoles;
    }

    /**
     * @return the current List of available CA identifiers as determined by state of dependencies
     */
    private List<Integer> getAvailableCertificateAuthorities() {
        final List<Integer> ret = new ArrayList<>();
        // Get all available CAs from the selected EEP
        final EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile != null) {
            final String[] availableCAsFromEEPArray = endEntityProfile.getValue(EndEntityProfile.AVAILCAS, 0).split(EndEntityProfile.SPLITCHAR);
            final boolean anyCAAvailableFromEEP = availableCAsFromEEPArray.length == 1 && availableCAsFromEEPArray[0].equalsIgnoreCase(String.valueOf(SecConst.ALLCAS));
            // Get all available CAs from the selected CP
            final CertificateProfile certificateProfile = getCertificateProfile();
            if (certificateProfile != null) {
                final List<Integer> availableCAsFromCP = certificateProfile.getAvailableCAs();
                final boolean anyCAAvailableFromCP = availableCAsFromCP.size() == 1 && availableCAsFromCP.iterator().next() == CertificateProfile.ANYCA;
                for (final KeyToValueHolder<CAInfo> tuple : authorizedCAInfos.values()) {
                    if ((anyCAAvailableFromEEP || Arrays.asList(availableCAsFromEEPArray).contains(String.valueOf(tuple.getId())))
                            && (anyCAAvailableFromCP || availableCAsFromCP.contains(tuple.getId()))) {
                        ret.add(tuple.getId());
                    }
                }
            }
        }
        return ret;
    }

    public List<SelectItem> getAvailableTokenTypesSelectItems() {
        final List<SelectItem> ret = new ArrayList<>();
        if (selectedTokenType == 0) {
            setSelectedTokenType(getDefaultTokenType());
        }
        ret.add(new SelectItem(0, raLocaleBean.getMessage(ENROLL_SELECT_KA_NOCHOICE), raLocaleBean.getMessage(ENROLL_SELECT_KA_NOCHOICE), true));
        ret.addAll(
                getAvilableTokenTypes()
                        .stream()
                        .map(id -> new SelectItem(id, raLocaleBean.getMessage("tokentype_" + id)))
                        .collect(Collectors.toList())
        );
        return ret;
    }

    private List<Integer> getAvilableTokenTypes() {
        List<Integer> ret = new ArrayList<>();
        final EndEntityProfile endEntityProfile = getEndEntityProfile();
        if (endEntityProfile != null) {
            ret = endEntityProfile.getAvailableTokenTypes();
        }
        return ret;
    }

    /**
     * @return the current selectedCertificateProfile as determined by state of dependencies
     */
    public String getSelectedCertificateProfile() {
        final List<Integer> availableCertificateProfiles = getAvailableCertificateProfiles();
        if (availableCertificateProfiles.size() == 1) {
            setSelectedCertificateProfile(String.valueOf(availableCertificateProfiles.get(0)));
        }
        if (StringUtils.isNotEmpty(selectedCertificateProfile)) {
            if (!availableCertificateProfiles.contains(Integer.parseInt(selectedCertificateProfile))) {
                setSelectedCertificateProfile(null);
            }
        }
        return selectedCertificateProfile;
    }

    /**
     * @param selectedCertificateProfile the selectedCertificateProfile to set
     */
    public void setSelectedCertificateProfile(final String selectedCertificateProfile) {
        if (!StringUtils.equals(selectedCertificateProfile, this.selectedCertificateProfile)) {
            // When ever the certificate profile changes this affects the available key algorithms
            availableAlgorithmSelectItems = null;
            // ...and any uploaded CSR needs to be revalidated (and we can do this by forcing a re-upload)
            resetAlgorithmCsrUpload();
        }
        this.selectedCertificateProfile = selectedCertificateProfile;
    }

    /**
     * @return the current availableAlgorithms as determined by state of dependencies
     */
    public List<SelectItem> getAvailableAlgorithmSelectItems() {
        if (this.availableAlgorithmSelectItems == null) {
            final List<SelectItem> availableAlgorithmSelectItems = new ArrayList<>();
            final CertificateProfile certificateProfile = getCertificateProfile();
            if (certificateProfile != null) {
                final List<String> availableKeyAlgorithms = certificateProfile.getAvailableKeyAlgorithmsAsList();
                final List<Integer> availableBitLengths = certificateProfile.getAvailableBitLengthsAsList();
                if (availableKeyAlgorithms.contains(AlgorithmConstants.KEYALGORITHM_DSA)) {
                    for (final int availableBitLength : availableBitLengths) {
                        if (availableBitLength == 1024) {
                            availableAlgorithmSelectItems.add(new SelectItem(AlgorithmConstants.KEYALGORITHM_DSA + "_" + availableBitLength,
                                    AlgorithmConstants.KEYALGORITHM_DSA + " " + availableBitLength + " bits"));
                        }
                    }
                }
                if (availableKeyAlgorithms.contains(AlgorithmConstants.KEYALGORITHM_RSA)) {
                    for (final int availableBitLength : availableBitLengths) {
                        if (availableBitLength >= 1024) {
                            availableAlgorithmSelectItems.add(new SelectItem(AlgorithmConstants.KEYALGORITHM_RSA + "_" + availableBitLength,
                                    AlgorithmConstants.KEYALGORITHM_RSA + " " + availableBitLength + " bits"));
                        }
                    }
                }
                if (availableKeyAlgorithms.contains(AlgorithmConstants.KEYALGORITHM_ED25519)) {
                    availableAlgorithmSelectItems.add(new SelectItem(AlgorithmConstants.KEYALGORITHM_ED25519,
                            AlgorithmConstants.KEYALGORITHM_ED25519));
                }
                if (availableKeyAlgorithms.contains(AlgorithmConstants.KEYALGORITHM_ED448)) {
                    availableAlgorithmSelectItems.add(new SelectItem(AlgorithmConstants.KEYALGORITHM_ED448,
                            AlgorithmConstants.KEYALGORITHM_ED448));
                }
                if (availableKeyAlgorithms.contains(AlgorithmConstants.KEYALGORITHM_ECDSA)) {
                    final Set<String> ecChoices = new HashSet<>();
                    if (certificateProfile.getAvailableEcCurvesAsList().contains(CertificateProfile.ANY_EC_CURVE)) {
                        for (final String ecNamedCurve : AlgorithmTools.getNamedEcCurvesMap(false).keySet()) {
                            if (CertificateProfile.ANY_EC_CURVE.equals(ecNamedCurve)) {
                                continue;
                            }
                            final int bitLength = AlgorithmTools.getNamedEcCurveBitLength(ecNamedCurve);
                            if (availableBitLengths.contains(Integer.valueOf(bitLength))) {
                                ecChoices.add(ecNamedCurve);
                            }
                        }
                    }
                    ecChoices.addAll(certificateProfile.getAvailableEcCurvesAsList());
                    ecChoices.remove(CertificateProfile.ANY_EC_CURVE);
                    final List<String> ecChoicesList = new ArrayList<>(ecChoices);
                    Collections.sort(ecChoicesList);
                    for (final String ecNamedCurve : ecChoicesList) {
                        if (!AlgorithmTools.isKnownAlias(ecNamedCurve)) {
                            log.warn("Ignoring unknown curve " + ecNamedCurve + " from being displayed in the RA web.");
                            continue;
                        }
                        availableAlgorithmSelectItems.add(new SelectItem(AlgorithmConstants.KEYALGORITHM_ECDSA + "_" + ecNamedCurve, AlgorithmConstants.KEYALGORITHM_ECDSA + " "
                                + StringTools.getAsStringWithSeparator(" / ", AlgorithmTools.getAllCurveAliasesFromAlias(ecNamedCurve))));
                    }
                }
                for (final String algName : CesecoreConfiguration.getExtraAlgs()) {
                    if (availableKeyAlgorithms.contains(CesecoreConfiguration.getExtraAlgTitle(algName))) {
                        for (final String subAlg : CesecoreConfiguration.getExtraAlgSubAlgs(algName)) {
                            final String name = CesecoreConfiguration.getExtraAlgSubAlgName(algName, subAlg);
                            final int bitLength = AlgorithmTools.getNamedEcCurveBitLength(name);
                            if (availableBitLengths.contains(Integer.valueOf(bitLength))) {
                                availableAlgorithmSelectItems.add(new SelectItem(CesecoreConfiguration.getExtraAlgTitle(algName) + "_" + name,
                                        CesecoreConfiguration.getExtraAlgSubAlgTitle(algName, subAlg)));
                            } else {
                                if (log.isTraceEnabled()) {
                                    log.trace("Excluding " + name + " from enrollment options since bit length " + bitLength + " is not available.");
                                }
                            }
                        }
                    }
                }
                if (availableAlgorithmSelectItems.size() > 1 && StringUtils.isEmpty(getSelectedAlgorithm(availableAlgorithmSelectItems))) {
                    availableAlgorithmSelectItems.add(new SelectItem(null, raLocaleBean.getMessage(ENROLL_SELECT_KA_NOCHOICE), raLocaleBean.getMessage(ENROLL_SELECT_KA_NOCHOICE), true));
                }
            }
            EnrollMakeNewRequestBean.sortSelectItemsByLabel(availableAlgorithmSelectItems);
            this.availableAlgorithmSelectItems = availableAlgorithmSelectItems;
        }
        return availableAlgorithmSelectItems;
    }

    /**
     * Sort the provided list by label with the exception of any item with null value that ends up first.
     */
    protected static void sortSelectItemsByLabel(final List<SelectItem> items) {
        Collections.sort(items, new Comparator<SelectItem>() {
            @Override
            public int compare(final SelectItem item1, final SelectItem item2) {
                if (item1.getValue() == null || (item1.getValue() instanceof String && ((String) item1.getValue()).isEmpty())) {
                    return Integer.MIN_VALUE;
                } else if (item2.getValue() == null || (item2.getValue() instanceof String && ((String) item2.getValue()).isEmpty())) {
                    return Integer.MAX_VALUE;
                }
                if (item1.getLabel() == null) {
                    return Integer.MIN_VALUE;
                }
                return item1.getLabel().compareToIgnoreCase(item2.getLabel());
            }
        });
    }

    public void setSelectedTokenType(int tokenType) {
        this.selectedTokenType = tokenType;
    }

    public int getSelectedTokenType() {
        return this.selectedTokenType;
    }

    public int getDefaultTokenType() {
        return getEndEntityProfile().getDefaultTokenType();
    }

    /**
     * @return the current selectedAlgorithm as determined by state of dependencies
     */
    public String getSelectedAlgorithm() {
        return getSelectedAlgorithm(getAvailableAlgorithmSelectItems());
    }

    /**
     * @return the current selectedAlgorithm as determined by state of dependencies
     */
    private String getSelectedAlgorithm(final List<SelectItem> availableAlgorithmSelectItems) {
        if (availableAlgorithmSelectItems.size() == 1) {
            selectedAlgorithm = String.valueOf(availableAlgorithmSelectItems.get(0).getValue());
        }
        if (StringUtils.isNotEmpty(selectedAlgorithm)) {
            boolean found = false;
            for (final SelectItem selectItem : availableAlgorithmSelectItems) {
                if (selectedAlgorithm.equals(selectItem.getValue())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                selectedAlgorithm = null;
            }
        }
        return selectedAlgorithm;
    }

    /**
     * @param selectedAlgorithm the selectedAlgorithm to set
     */
    public void setSelectedAlgorithm(final String selectedAlgorithm) {
        if (StringUtils.isNotEmpty(selectedAlgorithm)) {
            this.selectedAlgorithm = selectedAlgorithm;
        }
    }

    /**
     * @return the endEntityInformation
     */
    public EndEntityInformation getEndEntityInformation() {
        if (endEntityInformation == null) {
            endEntityInformation = new EndEntityInformation();
            if (!StringUtils.isBlank(getEndEntityProfile().getUsernameDefault())) {
                endEntityInformation.setUsername(getEndEntityProfile().getUsernameDefault());
            }
        }
        return endEntityInformation;
    }

    /**
     * @param endEntityInformation the endEntityInformation to set
     */
    public void setEndEntityInformation(EndEntityInformation endEntityInformation) {
        this.endEntityInformation = endEntityInformation;
    }


    /**
     * @return The extension data read from the extended information.
     */
    public String getExtensionData() {
        return this.extensionData;
    }

    public void setExtensionData(String extensionData) {
        this.extensionData = extensionData;
    }

    public String getAccountBindingId() {
        return accountBindingId;
    }

    public void setAccountBindingId(String accountBindingId) {
        this.accountBindingId = accountBindingId;
    }

    public String getPsd2NcaName() {
        return psd2NcaName;
    }

    public void setPsd2NcaName(final String psd2NcaName) {
        this.psd2NcaName = psd2NcaName;
    }

    public String getPsd2NcaId() {
        return psd2NcaId;
    }

    public void setPsd2NcaId(final String psd2NcaId) {
        this.psd2NcaId = psd2NcaId;
    }

    public List<String> getPsd2PspRoles() {
        return selectedPsd2PspRoles == null ? new ArrayList<>() : selectedPsd2PspRoles;
    }

    public void setPsd2PspRoles(List<String> roles) {
        selectedPsd2PspRoles = new ArrayList<>(roles);
    }

    public String getCabfOrganizationIdentifier() {
        return cabfOrganizationIdentifier;
    }

    public void setCabfOrganizationIdentifier(final String cabfOrganizationIdentifier) {
        this.cabfOrganizationIdentifier = cabfOrganizationIdentifier;
    }

    public boolean isCabfOrganizationIdentifierRendered() {
        return getEndEntityProfile().isCabfOrganizationIdentifierUsed() &&
                (isRenderNonModifiableFields() || isCabfOrganizationIdentifierModifiable());
    }

    public boolean isCabfOrganizationIdentifierModifiable() {
        return getEndEntityProfile().isCabfOrganizationIdentifierModifiable();
    }

    public boolean isCabfOrganizationIdentifierRequired() {
        return getEndEntityProfile().isCabfOrganizationIdentifierRequired();
    }

    /**
     * @return validation regex for the CA/B Forum Organization Identifier field
     */
    public String getCabfOrganizationIdentifierRegex() {
        return CabForumOrganizationIdentifier.VALIDATION_REGEX;
    }

    /**
     * @return the confirmPassword
     */
    public String getConfirmPassword() {
        return confirmPassword;
    }

    /**
     * @param confirmPassword the confirmPassword to set
     */
    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    public boolean isRequestPreviewMoreDetails() {
        return requestPreviewMoreDetails;
    }

    public boolean isSetCustomValidity() {
        return setCustomValidity;
    }

    public void setRequestPreviewMoreDetails(boolean requestPreviewMoreDetails) {
        this.requestPreviewMoreDetails = requestPreviewMoreDetails;
    }

    /**
     * @return the current selectedCertificateAuthority as determined by state of dependencies
     */
    public String getSelectedCertificateAuthority() {
        final List<Integer> availableCertificateAuthorities = getAvailableCertificateAuthorities();
        if (availableCertificateAuthorities.size() == 1) {
            selectedCertificateAuthority = String.valueOf(availableCertificateAuthorities.get(0));
        }
        if (StringUtils.isNotEmpty(selectedCertificateAuthority)) {
            if (!availableCertificateAuthorities.contains(Integer.parseInt(selectedCertificateAuthority))) {
                selectedCertificateAuthority = null;
            }
        }
        if (StringUtils.isBlank(selectedCertificateAuthority) && !availableCertificateAuthorities.isEmpty()){
            for (int certAuth: availableCertificateAuthorities){
                if (certAuth == getEndEntityProfile().getDefaultCA()){
                    selectedCertificateAuthority= String.valueOf(getEndEntityProfile().getDefaultCA());
                }
            }
        }
        return selectedCertificateAuthority;
    }

    /**
     * @param selectedCertificateAuthority the selectedCertificateAuthority to set
     */
    public void setSelectedCertificateAuthority(String selectedCertificateAuthority) {
        if (StringUtils.isNotEmpty(selectedCertificateAuthority)) {
            this.selectedCertificateAuthority = selectedCertificateAuthority;
        }
    }

    /**
     * @return the cached authorized certificate profiles
     */
    private IdNameHashMap<CertificateProfile> getAuthorizedCertificateProfiles() {
        return authorizedCertificateProfiles;
    }

    /**
     * Request info is rendered if any SDN or SAN fields exists
     *
     * @return true if request info block should be rendered
     */
    public boolean isRequestInfoRendered() {
        return isSubjectDnRendered() || isSubjectAlternativeNameRendered();
    }

    /**
     * @return the if there is at least one field in subject dn that should be rendered as determined by state of dependencies
     */
    public boolean isSubjectDnRendered() {
        if (getSubjectDn() != null) {
            for (final FieldInstance fieldInstance : getSubjectDn().getFieldInstances()) {
                if (isFieldInstanceRendered(fieldInstance)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return the current Subject DN as determined by state of dependencies
     */
    public SubjectDn getSubjectDn() {
        if (subjectDn == null) {
            final EndEntityProfile endEntityProfile = getEndEntityProfile();
            final CertificateProfile certificateProfile = getCertificateProfile();
            final X509CAInfo x509cainfo = (X509CAInfo) getCAInfo();
            if (endEntityProfile != null && certificateProfile != null && x509cainfo != null) {
                subjectDn = new SubjectDn(endEntityProfile);
                subjectDn.setLdapOrder(x509cainfo.getUseLdapDnOrder() && certificateProfile.getUseLdapDnOrder());
                subjectDn.setNameStyle(x509cainfo.getUsePrintableStringSubjectDN() ? PrintableStringNameStyle.INSTANCE : CeSecoreNameStyle.INSTANCE);
            }
        }
        return subjectDn;
    }

    /**
     * @return the if there is at least one field in subjectAlternativeName that should be rendered as determined by state of dependencies
     */
    public boolean isSubjectAlternativeNameRendered() {
        if (getSubjectAlternativeName() != null) {
            for (final FieldInstance fieldInstance : getSubjectAlternativeName().getFieldInstances()) {
                if (isFieldInstanceRendered(fieldInstance)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return the current Subject Alternative Name as determined by state of dependencies
     */
    public SubjectAlternativeName getSubjectAlternativeName() {
        if (subjectAlternativeName == null) {
            final EndEntityProfile endEntityProfile = getEndEntityProfile();
            if (endEntityProfile != null) {
                subjectAlternativeName = new SubjectAlternativeName(endEntityProfile);
            }
        }
        return subjectAlternativeName;
    }

    /**
     * @return the if there is at least one field in subject directory attributes that should be rendered
     */
    public boolean isSubjectDirectoryAttributesRendered() {
        if (getSubjectDirectoryAttributes()!=null) {
            for (final FieldInstance fieldInstance : getSubjectDirectoryAttributes().getFieldInstances()) {
                if (isFieldInstanceRendered(fieldInstance)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return the current Subject Directory Attributes as determined by state of dependencies
     */
    public SubjectDirectoryAttributes getSubjectDirectoryAttributes() {
        if (subjectDirectoryAttributes == null) {
            final EndEntityProfile endEntityProfile = getEndEntityProfile();
            if (endEntityProfile != null) {
                subjectDirectoryAttributes = new SubjectDirectoryAttributes(endEntityProfile);
            }
        }
        return subjectDirectoryAttributes;
    }

    /**
     * @return true if the field instance should be rendered
     */
    public boolean isFieldInstanceRendered(final FieldInstance fieldInstance) {
        if (log.isTraceEnabled()) {
            log.trace("isFieldInstanceRendered name=" + fieldInstance.getName() + " used=" + fieldInstance.isUsed() + " selectable=" + fieldInstance.isSelectable() +
                    " modifiable=" + fieldInstance.isModifiable() + " selectableValues.size=" + (fieldInstance.getSelectableValues() == null ? 0 : fieldInstance.getSelectableValues().size()));
        }
        // For the email fields "used" means use EE email address
        if (fieldInstance.isUsed() || DnComponents.DNEMAILADDRESS.equals(fieldInstance.getName()) || DnComponents.RFC822NAME.equals(fieldInstance.getName())
                || DnComponents.DNSNAME.equals(fieldInstance.getName())) {
            if (isRenderNonModifiableFields()) {
                return true;
            }
            if ((!fieldInstance.isSelectable() && fieldInstance.isModifiable()) || (fieldInstance.isSelectable() && fieldInstance.getSelectableValues().size() > 1)
                    || (!fieldInstance.isModifiable() && (fieldInstance.getName().equals("RFC822NAME") || fieldInstance.getName().equals("UPN") || fieldInstance.getName().equals("DNSNAME")))) {
                return true;
            }
        }
        return false;
    }

    public UploadedFile getUploadFile() {
        return uploadFile;
    }

    public void setUploadFile(UploadedFile uploadFile) {
        this.uploadFile = uploadFile;
    }

    public String getPublicKeyModulus() {
        return publicKeyModulus;
    }

    public void setPublicKeyModulus(String publicKeyModulus) {
        this.publicKeyModulus = publicKeyModulus;
    }

    public String getPublicKeyExponent() {
        return publicKeyExponent;
    }

    public void setPublicKeyExponent(String publicKeyExponent) {
        this.publicKeyExponent = publicKeyExponent;
    }

    public String getSha256Fingerprint() {
        return sha256Fingerprint;
    }

    public void setSha256Fingerprint(String sha256Fingerprint) {
        this.sha256Fingerprint = sha256Fingerprint;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getCsrFileName() {
        return csrFileName;
    }

    public void setCsrFileName(String csrFileName) {
        this.csrFileName = csrFileName;
    }

    /**
     * @return the current certificateRequest if available
     */
    public String getCertificateRequest() {
        if (KeyPairGeneration.ON_SERVER.equals(getSelectedKeyPairGenerationEnum())) {
            certificateRequest = null;
        } else if (KeyPairGeneration.PROVIDED_BY_USER.equals(getSelectedKeyPairGenerationEnum())) {
            if (StringUtils.isEmpty(certificateRequest)) {
                // Multi-line place holders are not allowed according to https://www.w3.org/TR/html5/forms.html#the-placeholder-attribute
                certificateRequest = raLocaleBean.getMessage("enroll_upload_csr_placeholder");
            }
        }
        return certificateRequest;
    }

    /**
     * @param certificateRequest the certificateRequest to set
     */
    public void setCertificateRequest(final String certificateRequest) {
        this.certificateRequest = certificateRequest;
    }

    /**
     * @return the requestId
     */
    public int getRequestId() {
        return requestId;
    }

    /**
     * @param requestId the requestId to set
     */
    public void setRequestId(int requestId) {
        this.requestId = requestId;
    }

    /**
     * @return the current state of the request preview as determined by state of dependencies
     */
    public RaRequestPreview getRequestPreview() {
        RaRequestPreview requestPreview = new RaRequestPreview();
        requestPreview.updateSubjectDn(getSubjectDn());
        requestPreview.updateSubjectAlternativeName(getSubjectAlternativeName(), getEndEntityProfile());
        requestPreview.updateSubjectDirectoryAttributes(getSubjectDirectoryAttributes());
        requestPreview.setPublicKeyAlgorithm(getAlgorithm());
        requestPreview.updateCA(getCAInfo());
        requestPreview.updateCertificateProfile(getCertificateProfile());
        requestPreview.setMore(requestPreviewMoreDetails);
        return requestPreview;
    }

    /**
     * @return name of HTTP GET request parameter for checking approval status
     */
    public final String getParamRequestId() {
        return PARAM_REQUESTID;
    }

    public UIComponent getUserCredentialsMessagesComponent() {
        return userCredentialsMessagesComponent;
    }

    public void setUserCredentialsMessagesComponent(UIComponent userCredentialsMessagesComponent) {
        this.userCredentialsMessagesComponent = userCredentialsMessagesComponent;
    }

    public UIComponent getSubjectDnMessagesComponent() {
        return subjectDnMessagesComponent;
    }

    public void setSubjectDnMessagesComponent(UIComponent subjectDnMessagesComponent) {
        this.subjectDnMessagesComponent = subjectDnMessagesComponent;
    }

    /**
     * @return the confirmPasswordComponent
     */
    public UIComponent getConfirmPasswordComponent() {
        return confirmPasswordComponent;
    }

    /**
     * @param confirmPasswordComponent the confirmPasswordComponent to set
     */
    public void setConfirmPasswordComponent(UIComponent confirmPasswordComponent) {
        this.confirmPasswordComponent = confirmPasswordComponent;
    }

    public UIComponent getValidityInputComponent() {
        return validityInputComponent;
    }

    public void setValidityInputComponent(final UIComponent validityInputComponent) {
        this.validityInputComponent = validityInputComponent;
    }

    /**
     * Finds the UPN/RFC822 email and domain in an Ajax event, concatenates them and
     * sets the value of the appropriate FieldInstance.
     *
     * @param event the Ajax event
     */
    public void upnRfc(AjaxBehaviorEvent event) {
        UIComponent components = event.getComponent();
        UIInput emailInput = (UIInput) components.findComponent("upnRfcEmail");
        UIInput domainInput = (UIInput) components.findComponent("upnRfcDomain");
        int index = -1;
        String email = "";
        if (emailInput != null) {
            email = emailInput.getValue().toString();
            // Split the clientId on ':', the second to last substring is the loop index
            String[] split = emailInput.getClientId().split(":");
            index = Integer.parseInt(split[split.length - 2]);
        }
        String domain = "";
        if (domainInput != null) {
            domain = domainInput.getValue().toString();
        }
        String concatenated = "";
        if (!email.trim().isEmpty() && !domain.trim().isEmpty()) {
            concatenated = email + "@" + domain;
        }
        List<EndEntityProfile.FieldInstance> fieldInstances = (List<EndEntityProfile.FieldInstance>) subjectAlternativeName.getFieldInstances();
        if (index >= 0 && index < fieldInstances.size()) {
            fieldInstances.get(index).setValue(concatenated);
        }
    }

    /**
     * Clears or populates the auto completion menu list below the EAB ID 
     * field (max. 32 entries).  
     * 
     * @param event the AJAX event.
     */
    public void autoCompleteEabId(final AjaxBehaviorEvent event) {
        final String value = (String) ((UIInput) event.getSource()).getValue();
        eabIdAutoCompleteSelectItems.clear();
        if (value != null && value.length() > 2) {
            CertificateProfile cp = getCertificateProfile();
            if (cp == null) {
                final EndEntityProfile eep = getEndEntityProfile();
                if (eep != null) {
                    final int cpId = eep.getDefaultCertificateProfile();
                    if (cpId != -1 && authorizedCertificateProfiles.get(cpId) != null) {
                        cp = authorizedCertificateProfiles.get(cpId).getValue();
                    }
                }
            }
            if (cp != null && eabConfiguration != null) {
                final Set<String> eabNamespaces = cp.getEabNamespaces();
                final Set<String> ids = new HashSet<>();
                for (String namespace : eabNamespaces) {
                    ids.addAll(eabConfiguration.getEABMap().get(namespace));
                }
                if (ids != null && !ids.isEmpty()) {
                    eabIdAutoCompleteSelectItems.add(new SelectItem("..."));
                    ids.stream().filter(e -> e.toLowerCase().startsWith(value.toLowerCase())).sorted()
                            .limit(32).forEach(v -> eabIdAutoCompleteSelectItems.add(new SelectItem(v)));
                }
                if (log.isTraceEnabled()) {
                    String namespaces = String.join(",", eabNamespaces);
                    log.trace("Result set for EAB ID auto complete for namespaces '" + namespaces + "': "
                            + eabIdAutoCompleteSelectItems.stream().map(e -> e.getValue()).toArray());
                }
            }
        }
    }
    
    /**
     * Copies the selected value from the auto complete menu into he EAB ID text field.
     * 
     * @param event the AJAX event.
     */
    public void setAutoCompleteForEabId(final AjaxBehaviorEvent event) {
        final HtmlSelectOneMenu source = (HtmlSelectOneMenu) event.getSource();
        if (!"...".equals(source.getValue())) {
            accountBindingId = (String) source.getValue();
            eabIdAutoCompleteSelectItems.clear();
        }
        eabIdAutoComplete = "...";
    }
    
    private String eabIdAutoComplete;
    
    private List<SelectItem> eabIdAutoCompleteSelectItems = new ArrayList<>() ;

    public String getEabIdAutoComplete() {
        return eabIdAutoComplete;
    }

    public void setEabIdAutoComplete(String accountBindingIdAutoComplete) {
        this.eabIdAutoComplete = accountBindingIdAutoComplete;
    }

    public List<SelectItem> getEabIdAutoCompleteSelectItems() {
        return eabIdAutoCompleteSelectItems;
    }

    public void setEabIdAutoCompleteSelectItems(List<SelectItem> accountBindingIdAutoCompleteSelectItems) {
        this.eabIdAutoCompleteSelectItems = accountBindingIdAutoCompleteSelectItems;
    }
    
    public boolean getHasEabIdAutoCompleteSelectItems() {
        return eabIdAutoCompleteSelectItems.size() > 0;
    }
}

