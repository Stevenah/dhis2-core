package org.hisp.dhis.validation;

/*
 * Copyright (c) 2004-2016, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hisp.dhis.constant.ConstantService;
import org.hisp.dhis.dataelement.DataElementCategoryOptionCombo;
import org.hisp.dhis.dataelement.DataElementCategoryService;
import org.hisp.dhis.dataelement.DataElementOperand;
import org.hisp.dhis.dataset.DataSet;
import org.hisp.dhis.datavalue.DataValue;
import org.hisp.dhis.datavalue.DataValueService;
import org.hisp.dhis.i18n.I18nFormat;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.organisationunit.OrganisationUnitService;
import org.hisp.dhis.period.Period;
import org.hisp.dhis.period.PeriodService;
import org.hisp.dhis.setting.SettingKey;
import org.hisp.dhis.setting.SystemSettingManager;
import org.hisp.dhis.user.CurrentUserService;
import org.hisp.dhis.user.User;
import org.hisp.dhis.validation.notification.ValidationNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Jim Grace
 */
public class DefaultValidationService
    implements ValidationService
{
    private static final Log log = LogFactory.getLog( DefaultValidationService.class );

    @Autowired
    private PeriodService periodService;

    @Autowired
    private DataValueService dataValueService;

    @Autowired
    private DataElementCategoryService categoryService;

    @Autowired
    private ConstantService constantService;

    @Autowired
    private OrganisationUnitService organisationUnitService;

    @Autowired
    private ValidationNotificationService notificationService;

    @Autowired
    private SystemSettingManager systemSettingManager;

    @Autowired
    private ValidationRuleService validationRuleService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CurrentUserService currentUserService;

    public void setCurrentUserService( CurrentUserService currentUserService )
    {
        this.currentUserService = currentUserService;
    }

    // -------------------------------------------------------------------------
    // ValidationRule business logic
    // -------------------------------------------------------------------------

    @Override
    public Collection<ValidationResult> validate( Date startDate, Date endDate, Collection<OrganisationUnit> sources,
        DataElementCategoryOptionCombo attributeCombo, ValidationRuleGroup group, boolean sendNotifications, I18nFormat format )
    {
        log.debug( "Validate start:" + startDate + " end: " + endDate + " sources: " + sources.size() + " group: " + group );

        List<Period> periods = periodService.getPeriodsBetweenDates( startDate, endDate );

        Collection<ValidationRule> rules = group != null ? group.getMembers() : validationRuleService.getAllValidationRules();

        Collection<ValidationResult> results = runValidation( sources, periods, rules, attributeCombo, null, ValidationRunType.SCHEDULED );

        formatPeriods( results, format );

        if ( sendNotifications )
        {
            notificationService.sendNotifications( new HashSet<>( results ) );
        }

        return results;
    }

    @Override
    public Collection<ValidationResult> validate( DataSet dataSet, Period period, OrganisationUnit source,
        DataElementCategoryOptionCombo attributeCombo )
    {
        log.debug( "Validate data set: " + dataSet.getName() + " period: " + period.getPeriodType().getName() + " "
            + period.getStartDate() + " " + period.getEndDate() + " source: " + source.getName()
            + " attribute combo: " + (attributeCombo == null ? "[none]" : attributeCombo.getName()) );

        Collection<ValidationRule> rules = validationRuleService.getValidationRulesForDataElements( dataSet.getDataElements() );

        return runValidation(
            Sets.newHashSet( source ), Sets.newHashSet( period ), rules, attributeCombo, null, ValidationRunType.SCHEDULED );
    }

    @Override
    public void scheduledRun()
    {
        log.info( "Starting scheduled monitoring task" );

        List<OrganisationUnit> sources = organisationUnitService.getAllOrganisationUnits();

        // Find all rules which might generate notifications
        Set<ValidationRule> rules = getValidationRulesWithNotificationTemplates();

        Set<Period> periods = extractNotificationPeriods( rules );

        Date lastScheduledRun = (Date) systemSettingManager.getSystemSetting( SettingKey.LAST_MONITORING_RUN );

        // Any database changes after this moment will contribute to the next run.

        Date thisRun = new Date();

        log.info( "Scheduled monitoring run sources: " + sources.size() + ", periods: " + periods.size() + ", rules:" + rules.size()
            + ", last run: " + (lastScheduledRun == null ? "[none]" : lastScheduledRun) );

        Collection<ValidationResult> results =
            runValidation( sources, periods, rules, null, lastScheduledRun, ValidationRunType.SCHEDULED );

        log.info( "Validation run result count: " + results.size() );

        notificationService.sendNotifications( new HashSet<>( results ) );

        log.info( "Sent notifications, monitoring task done" );

        systemSettingManager.saveSystemSetting( SettingKey.LAST_MONITORING_RUN, thisRun );
    }

    public List<DataElementOperand> validateRequiredComments(
        DataSet dataSet, Period period, OrganisationUnit organisationUnit, DataElementCategoryOptionCombo attributeOptionCombo )
    {
        if ( !dataSet.isNoValueRequiresComment() )
        {
            return Collections.emptyList();
        }

        // Go through all DEs of the data set and find any violations
        return dataSet.getDataElements().stream()
            .flatMap( de -> de.getCategoryOptionCombos().stream()
                .filter( co -> {
                    DataValue dv = dataValueService.getDataValue( de, period, organisationUnit, co, attributeOptionCombo );
                    return dv == null || ( StringUtils.isBlank( dv.getValue() ) && StringUtils.isBlank( dv.getComment() ) );
                } ).map( co -> new DataElementOperand( de, co ) )
            )
            .collect( Collectors.toList() );
    }

    // -------------------------------------------------------------------------
    // Supportive methods
    // -------------------------------------------------------------------------

    private Collection<ValidationResult> runValidation(
        Collection<OrganisationUnit> sources,
        Collection<Period> periods,
        Collection<ValidationRule> rules,
        DataElementCategoryOptionCombo attributeOptionCombo,
        Date lastScheduledRun,
        ValidationRunType runType )
    {
        User user = currentUserService.getCurrentUser();

        ValidationRunContext ctx = ValidationRunContext.getNewContext(
            sources,
            periods,
            rules,
            attributeOptionCombo,
            lastScheduledRun,
            runType,
            constantService.getConstantMap(),
            rules.stream().collect( Collectors.toMap( Function.identity(), r -> validationRuleService.getDataElements( r ) ) ),
            user == null ? null : categoryService.getCogDimensionConstraints( user.getUserCredentials() ),
            user == null ? null : categoryService.getCoDimensionConstraints( user.getUserCredentials() )
        );

        return Validator.validate( ctx, applicationContext );
    }

    private Set<ValidationRule> getValidationRulesWithNotificationTemplates()
    {
        return Sets.newHashSet( validationRuleService.getValidationRulesWithNotificationTemplates() );
    }

    /**
     * Get the current and most recent periods to use when performing validation
     * for generating notifications (previously 'alerts').
     *
     * The periods are filtered against existing (persisted) periods.
     *
     * TODO Consider:
     *      This method assumes that the last successful validation run was one day ago.
     *      If this is not the case (more than one day ago) adding additional (daily)
     *      periods to 'fill the gap' could be considered.
     */
    private Set<Period> extractNotificationPeriods( Set<ValidationRule> rules )
    {
        return rules.stream()
            .map( rule -> periodService.getPeriodTypeByName( rule.getPeriodType().getName() ) )
            .map( periodType -> {
                Period current = periodType.createPeriod(), previous = periodType.getPreviousPeriod( current );
                Date start = previous.getStartDate(), end = current.getEndDate();

                return periodService.getIntersectingPeriodsByPeriodType( periodType, start, end );
            } )
            .flatMap( Collection::stream )
            .collect( Collectors.toSet() );
    }

    /**
     * Formats and sets name on the period of each result.
     *
     * @param results the collection of validation results.
     * @param format  the i18n format.
     */
    private void formatPeriods( Collection<ValidationResult> results, I18nFormat format )
    {
        if ( format != null )
        {
            for ( ValidationResult result : results )
            {
                if ( result != null && result.getPeriod() != null )
                {
                    result.getPeriod().setName( format.formatPeriod( result.getPeriod() ) );
                }
            }
        }
    }
}