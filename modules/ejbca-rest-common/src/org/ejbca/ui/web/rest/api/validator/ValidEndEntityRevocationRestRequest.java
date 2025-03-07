/*************************************************************************
 *                                                                       *
 *  EJBCA - Proprietary Modules: Enterprise Certificate Authority        *
 *                                                                       *
 *  Copyright (c), PrimeKey Solutions AB. All rights reserved.           *
 *  The use of the Proprietary Modules are subject to specific           *
 *  commercial license terms.                                            *
 *                                                                       *
 *************************************************************************/
package org.ejbca.ui.web.rest.api.validator;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.validation.Constraint;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.validation.Payload;

import org.apache.commons.lang.StringUtils;
import org.cesecore.certificates.crl.RevocationReasons;
import org.ejbca.ui.web.rest.api.io.request.EndEntityRevocationRestRequest;
import org.ejbca.ui.web.rest.api.io.request.SetEndEntityStatusRestRequest;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validation annotation for input parameter with built-in validator. An input SetEndEntityRevocationRestRequest is validated for:
 * <ul>
 *     <li>Not null.</li>
 * </ul>
 *
 * SetEndEntityRevocationRestRequest reason attribute is validated for:
 * <ul>
 *     <li>Not null;</li>
 *     <li>Not empty;</li>
 *     <li>The value has to be one of RevocationReasons (see RFC5280 section 5.3.1).</li>
 * </ul>
 * 
 */
@Target({TYPE, FIELD, PARAMETER})
@Retention(RUNTIME)
@Constraint(validatedBy = {ValidEndEntityRevocationRestRequest.Validator.class})
@Documented
public @interface ValidEndEntityRevocationRestRequest {

    String message() default "{ValidRevocationEndEntityRestRequest.invalid.default}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidEndEntityRevocationRestRequest, EndEntityRevocationRestRequest> {

        @Override
        public void initialize(final ValidEndEntityRevocationRestRequest validEditEndEntityRestRequest) {
        }

        @Override
        public boolean isValid(final EndEntityRevocationRestRequest revokeEndEntityRestRequest, final ConstraintValidatorContext constraintValidatorContext) {
            if (revokeEndEntityRestRequest == null) {
                ValidationHelper.addConstraintViolation(constraintValidatorContext, "{ValidRevocationEndEntityRestRequest.invalid.null}");
                return false;
            }
            final int reasonCode = revokeEndEntityRestRequest.getReasonCode();
            RevocationReasons reason = RevocationReasons.getFromDatabaseValue(reasonCode);
            if (reason == null) {
                ValidationHelper.addConstraintViolation(constraintValidatorContext, "{ValidRevocationEndEntityRestRequest.invalid.reason.unknown}");
                return false;
            }
            return true;
        }
    }
}
