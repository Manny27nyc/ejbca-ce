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

package org.ejbca.core.ejb.ra;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.cesecore.CesecoreException;
import org.cesecore.ErrorCode;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.authorization.AuthorizationSessionLocal;
import org.cesecore.authorization.control.StandardRules;
import org.cesecore.certificates.ca.CADoesntExistsException;
import org.cesecore.certificates.ca.CAInfo;
import org.cesecore.certificates.ca.CaSessionLocal;
import org.cesecore.certificates.ca.ExtendedUserDataHandler;
import org.cesecore.certificates.ca.IllegalNameException;
import org.cesecore.certificates.ca.SignRequestSignatureException;
import org.cesecore.certificates.ca.X509CAInfo;
import org.cesecore.certificates.certificate.CertificateConstants;
import org.cesecore.certificates.certificate.certextensions.CertificateExtensionException;
import org.cesecore.certificates.certificate.exception.CertificateSerialNumberException;
import org.cesecore.certificates.certificate.request.CertificateResponseMessage;
import org.cesecore.certificates.certificate.request.RequestMessage;
import org.cesecore.certificates.certificate.request.RequestMessageUtils;
import org.cesecore.certificates.certificate.request.ResponseMessage;
import org.cesecore.certificates.certificate.request.X509ResponseMessage;
import org.cesecore.certificates.certificateprofile.CertificateProfileSessionLocal;
import org.cesecore.certificates.endentity.EndEntityConstants;
import org.cesecore.certificates.endentity.EndEntityInformation;
import org.cesecore.configuration.GlobalConfigurationSessionLocal;
import org.cesecore.jndi.JndiConstants;
import org.cesecore.util.CertTools;
import org.ejbca.config.GlobalConfiguration;
import org.ejbca.core.EjbcaException;
import org.ejbca.core.ejb.ca.auth.EndEntityAuthenticationSessionLocal;
import org.ejbca.core.ejb.ca.sign.SignSessionLocal;
import org.ejbca.core.ejb.keyrecovery.KeyRecoverySessionLocal;
import org.ejbca.core.ejb.ra.raadmin.EndEntityProfileSessionLocal;
import org.ejbca.core.model.InternalEjbcaResources;
import org.ejbca.core.model.SecConst;
import org.ejbca.core.model.approval.ApprovalException;
import org.ejbca.core.model.approval.WaitingForApprovalException;
import org.ejbca.core.model.authorization.AccessRulesConstants;
import org.ejbca.core.model.ca.WrongTokenTypeException;
import org.ejbca.core.model.ra.CustomFieldException;
import org.ejbca.core.model.ra.NotFoundException;
import org.ejbca.core.model.ra.raadmin.EndEntityProfile;
import org.ejbca.core.model.ra.raadmin.EndEntityProfileValidationException;
import org.ejbca.cvc.exception.ConstructionException;
import org.ejbca.cvc.exception.ParseException;

import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.SessionContext;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;

/**
 * Combines EditUser (RA) with CertReq (CA) methods using transactions.
 */
@Stateless(mappedName = JndiConstants.APP_JNDI_PREFIX + "CertificateRequestSessionRemote")
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class CertificateRequestSessionBean implements CertificateRequestSessionRemote, CertificateRequestSessionLocal {

    private static final Logger log = Logger.getLogger(CertificateRequestSessionBean.class);

    /** Internal localization of logs and errors */
    private static final InternalEjbcaResources intres = InternalEjbcaResources.getInstance();

    @EJB
    private AuthorizationSessionLocal authorizationSession;
    @EJB
    private CaSessionLocal caSession;
    @EJB
    private CertificateProfileSessionLocal certificateProfileSession;
    @EJB
    private EndEntityAuthenticationSessionLocal authenticationSession;
    @EJB
    private EndEntityAccessSessionLocal endEntityAccessSession;
    @EJB
    private EndEntityProfileSessionLocal endEntityProfileSession;
    @EJB
    private KeyRecoverySessionLocal keyRecoverySession;
    @EJB
    private KeyStoreCreateSessionLocal keyStoreCreateSession;
    @EJB
    private GlobalConfigurationSessionLocal globalConfigurationSession;
    @EJB
    private EndEntityManagementSessionLocal endEntityManagementSession;
    @EJB
    private SignSessionLocal signSession;
    @Resource
    private SessionContext sessionContext;

    @Override
    public byte[] processCertReq(AuthenticationToken admin, EndEntityInformation userdata, String req, int reqType, int responseType) 
            throws AuthorizationDeniedException, NotFoundException, InvalidKeyException, NoSuchAlgorithmException,
            InvalidKeySpecException, NoSuchProviderException, SignatureException, IOException, CertificateException,
            EndEntityProfileValidationException, ApprovalException, EjbcaException, CesecoreException, CertificateExtensionException {
        byte[] retval = null;

        // Check tokentype
        if (userdata.getTokenType() != SecConst.TOKEN_SOFT_BROWSERGEN) {
            throw new WrongTokenTypeException("Error: Wrong Token Type of user, must be 'USERGENERATED' for PKCS10/SPKAC/CRMF/CVC requests");
        }
        
        String password = userdata.getPassword();
        String username = userdata.getUsername();
        RequestMessage requestMessage;
        try {
            requestMessage = RequestMessageUtils.getRequestMessageFromType(username, password, req, reqType);
        } catch (InvalidKeyException | SignRequestSignatureException | NoSuchAlgorithmException | NoSuchProviderException | SignatureException
                | IOException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw e;
        } catch (ParseException | ConstructionException | NoSuchFieldException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw new EjbcaException(ErrorCode.FIELD_VALUE_NOT_VALID, e);
        }
        CAInfo cainfo = caSession.getCAInfoInternal(userdata.getCAId());
        if(cainfo.getCAType() == CAInfo.CATYPE_X509) {
            String preProcessorClass = ((X509CAInfo) cainfo).getRequestPreProcessor();
            if (!StringUtils.isEmpty(preProcessorClass)) {
                try {
                    ExtendedUserDataHandler extendedUserDataHandler = (ExtendedUserDataHandler) Class.forName(preProcessorClass).newInstance();
                    requestMessage = extendedUserDataHandler.processRequestMessage(requestMessage, certificateProfileSession.getCertificateProfileName(userdata.getCertificateProfileId()));
                    userdata.setDN(requestMessage.getRequestX500Name().toString());
                } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                    throw new IllegalStateException("Request Preprocessor implementation " + preProcessorClass + " could not be instansiated.");
                }

            }
        }
        
        // This is the secret sauce, do the end entity handling automagically here before we get the cert
        addOrEditUser(admin, userdata, false, true);
        // Process request
        try {
            
            if (requestMessage != null) {
                // If no username is supplied initially and the EEP has autogenerated username,
                // requestMessage should be updated. RequestMessage is created before
                // autogenerated username is calculated in addOrEditUser.
                EndEntityProfile entityProfile = endEntityProfileSession.getEndEntityProfile(userdata.getEndEntityProfileId());
                if (StringUtils.isEmpty(username) && entityProfile.isAutoGeneratedUsername()) {
                    requestMessage.setUsername(userdata.getUsername());
                }
                // The same goes for password
                if (StringUtils.isEmpty(password) && entityProfile.useAutoGeneratedPasswd()) {
                    requestMessage.setPassword(userdata.getPassword());
                }
                retval = getCertResponseFromPublicKey(admin, requestMessage, responseType, userdata);
            }
        } catch (CertificateExtensionException | CertificateException | EjbcaException | CesecoreException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw e;
        } 
        return retval;
    }

    @Override
    public ResponseMessage processCertReq(AuthenticationToken admin, EndEntityInformation userdata, RequestMessage req, Class<? extends CertificateResponseMessage> responseClass)
            throws EndEntityExistsException, AuthorizationDeniedException, EndEntityProfileValidationException, EjbcaException, CesecoreException, CertificateExtensionException {
        // Check tokentype
        if (userdata.getTokenType() != SecConst.TOKEN_SOFT_BROWSERGEN) {
            throw new WrongTokenTypeException("Error: Wrong Token Type of user, must be 'USERGENERATED' for PKCS10/SPKAC/CRMF/CVC requests");
        }
        CAInfo cainfo = caSession.getCAInfoInternal(userdata.getCAId());
        if(cainfo.getCAType() == CAInfo.CATYPE_X509) {
            String preProcessorClass = ((X509CAInfo) cainfo).getRequestPreProcessor();
            if (!StringUtils.isEmpty(preProcessorClass)) {
                try {
                    ExtendedUserDataHandler extendedUserDataHandler = (ExtendedUserDataHandler) Class.forName(preProcessorClass).newInstance();
                    req = extendedUserDataHandler.processRequestMessage(req, certificateProfileSession.getCertificateProfileName(userdata.getCertificateProfileId()));
                    userdata.setDN(req.getRequestX500Name().toString());
                } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                    throw new IllegalStateException("Request Preprocessor implementation " + preProcessorClass + " could not be instansiated.");
                }

            }
        }
        
        // This is the secret sauce, do the end entity handling automagically here before we get the cert
        addOrEditUser(admin, userdata, false, true);
        ResponseMessage retval = null;
        try {
            retval = signSession.createCertificate(admin, req, responseClass, userdata);
        } catch (EjbcaException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw e;
        } catch (CertificateExtensionException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw e;
        }
        return retval;
    }

    /**
     * @throws CADoesntExistsException if userdata.caId is not a valid caid. This is checked in editUser or addUserFromWS
     * @throws IllegalNameException  if the Subject DN failed constraints
     * @throws CertificateSerialNumberException if SubjectDN serial number already exists.
     * @throws CustomFieldException if the end entity was not validated by a locally defined field validator
     * @throws NoSuchEndEntityException if the end entity was not found
     */
    private void addOrEditUser(AuthenticationToken admin, EndEntityInformation userdata, boolean clearpwd, boolean fromwebservice)
            throws AuthorizationDeniedException, EndEntityProfileValidationException, ApprovalException, EndEntityExistsException,
            CADoesntExistsException, CertificateSerialNumberException, IllegalNameException, CustomFieldException, NoSuchEndEntityException {

        int caid = userdata.getCAId();
        if (!authorizationSession.isAuthorizedNoLogging(admin, StandardRules.CAACCESS.resource() + caid)) {
            final String msg = intres.getLocalizedMessage("authorization.notauthorizedtoresource", StandardRules.CAACCESS.resource() + caid, null);
            throw new AuthorizationDeniedException(msg);
        }
        if (!authorizationSession.isAuthorizedNoLogging(admin, AccessRulesConstants.REGULAR_CREATECERTIFICATE)) {
            final String msg = intres.getLocalizedMessage("authorization.notauthorizedtoresource", AccessRulesConstants.REGULAR_CREATECERTIFICATE,
                    null);
            throw new AuthorizationDeniedException(msg);
        }
        // First we need to fetch the CA configuration to see if we save UserData, if not, we still run addUserFromWS to
        // get all the proper authentication checks for CA and end entity profile.
        // No need to to access control here just to fetch this flag, access control for the CA is done in EndEntityManagementSession
        boolean useUserStorage = caSession.getCAInfoInternal(caid, null, true).isUseUserStorage();
        // Add or edit user
        try {
            String username = userdata.getUsername();
            if (useUserStorage && endEntityManagementSession.existsUser(username)) {
                if (log.isDebugEnabled()) {
                    log.debug("End entity '" + username + "' exists, update the userdata. New status '" + userdata.getStatus() + "'.");
                }
                endEntityManagementSession.changeUser(admin, userdata, clearpwd, fromwebservice);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("New end entity '" + username + "', adding userdata. New status '" + userdata.getStatus() + "'.");
                }
                // addUserfromWS also checks useUserStorage internally, so don't duplicate the check
                endEntityManagementSession.addUserFromWS(admin, userdata, clearpwd);
            }
        } catch (WaitingForApprovalException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            String msg = "Single transaction enrollment request rejected since approvals are enabled for this CA (" + caid
                    + ") or Certificate Profile (" + userdata.getCertificateProfileId() + ").";
            log.info(msg);
            throw new ApprovalException(msg);
        }
    }

    /**
     * Process a request in the CA module.
     *
     * @param admin is the requesting administrator
     * @param msg is the request message processed by the CA
     * @param responseType is one of SecConst.CERT_RES_TYPE_...
     * @return a encoded certificate of the type specified in responseType
     * @throws AuthorizationDeniedException
     * @throws CertificateExtensionException if the request message contained invalid extensions
     */
    private byte[] getCertResponseFromPublicKey(AuthenticationToken admin, RequestMessage msg, int responseType,
            EndEntityInformation userData) throws EjbcaException, CesecoreException, CertificateEncodingException, CertificateException,
            AuthorizationDeniedException, CertificateExtensionException {
        byte[] retval = null;
        Class<X509ResponseMessage> respClass = X509ResponseMessage.class;
        ResponseMessage resp = signSession.createCertificate(admin, msg, respClass, userData);
        X509Certificate cert = CertTools.getCertfromByteArray(resp.getResponseMessage(), X509Certificate.class);
        if (responseType == CertificateConstants.CERT_RES_TYPE_CERTIFICATE) {
            retval = cert.getEncoded();
        }
        if (responseType == CertificateConstants.CERT_RES_TYPE_PKCS7) {
            retval = signSession.createPKCS7(admin, cert, false);
        }
        if (responseType == CertificateConstants.CERT_RES_TYPE_PKCS7WITHCHAIN) {
            retval = signSession.createPKCS7(admin, cert, true);
        }
        return retval;
    }

    @Override
    public byte[] processSoftTokenReq(AuthenticationToken admin, EndEntityInformation userdata, String keyspec, String keyalg,
            boolean createJKS) throws ApprovalException, EndEntityExistsException, CADoesntExistsException, CertificateSerialNumberException,
            IllegalNameException, CustomFieldException, AuthorizationDeniedException, EndEntityProfileValidationException, NoSuchAlgorithmException,
            InvalidKeySpecException, CertificateException, InvalidAlgorithmParameterException, KeyStoreException, NoSuchEndEntityException {

        // This is the secret sauce, do the end entity handling automagically here before we get the cert
        addOrEditUser(admin, userdata, false, true);
        // Process request
        byte[] ret = null;
        try {
            // Get key recovery info
            boolean usekeyrecovery = ( (GlobalConfiguration) globalConfigurationSession.getCachedConfiguration(GlobalConfiguration.GLOBAL_CONFIGURATION_ID)).getEnableKeyRecovery();
            if (log.isDebugEnabled()) {
                log.debug("usekeyrecovery: " + usekeyrecovery);
            }
            boolean savekeys = userdata.getKeyRecoverable() && usekeyrecovery && (userdata.getStatus() != EndEntityConstants.STATUS_KEYRECOVERY);
            if (log.isDebugEnabled()) {
                log.debug("userdata.getKeyRecoverable(): " + userdata.getKeyRecoverable());
                log.debug("userdata.getStatus(): " + userdata.getStatus());
                log.debug("savekeys: " + savekeys);
            }
            boolean loadkeys = (userdata.getStatus() == EndEntityConstants.STATUS_KEYRECOVERY) && usekeyrecovery;
            if (log.isDebugEnabled()) {
                log.debug("loadkeys: " + loadkeys);
            }
            int endEntityProfileId = userdata.getEndEntityProfileId();
            EndEntityProfile endEntityProfile = endEntityProfileSession.getEndEntityProfileNoClone(endEntityProfileId);
            boolean reusecertificate = endEntityProfile.getReUseKeyRecoveredCertificate();
            if (log.isDebugEnabled()) {
                log.debug("reusecertificate: " + reusecertificate);
            }
            // Generate keystore
            String password = userdata.getPassword();
            String username = userdata.getUsername();
            int caid = userdata.getCAId();
            KeyStore keyStore = keyStoreCreateSession.generateOrKeyRecoverToken(admin, username, password, caid, keyspec, keyalg, null, null,
                    createJKS ? SecConst.TOKEN_SOFT_JKS : SecConst.TOKEN_SOFT_P12, loadkeys, savekeys,
                    reusecertificate, endEntityProfileId);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            keyStore.store(baos, password.toCharArray());
            ret = baos.toByteArray();
        } catch (NoSuchAlgorithmException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw e;
        } catch (InvalidKeySpecException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw e;
        } catch (IOException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw new IllegalStateException(e);
        } catch (CertificateException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw e;
        } catch (InvalidAlgorithmParameterException e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            sessionContext.setRollbackOnly(); // This is an application exception so it wont trigger a roll-back automatically
            throw new KeyStoreException(e);
        }
        return ret;
    }
}
