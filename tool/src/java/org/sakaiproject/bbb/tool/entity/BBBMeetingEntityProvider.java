/**
 * Copyright (c) 2010 onwards - The Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sakaiproject.bbb.tool.entity;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;


import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.codec.binary.Base64;
import org.sakaiproject.authz.api.Member;
import org.sakaiproject.authz.api.Role;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.bbb.api.BBBException;
import org.sakaiproject.bbb.api.BBBMeetingManager;
import org.sakaiproject.bbb.api.Participant;
import org.sakaiproject.bbb.api.storage.BBBMeeting;
import org.sakaiproject.bbb.api.storage.BBBMeetingParticipant;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.entitybroker.EntityReference;
import org.sakaiproject.entitybroker.EntityView;
import org.sakaiproject.entitybroker.entityprovider.CoreEntityProvider;
import org.sakaiproject.entitybroker.entityprovider.annotations.EntityCustomAction;
import org.sakaiproject.entitybroker.entityprovider.capabilities.ActionsExecutable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.AutoRegisterEntityProvider;
import org.sakaiproject.entitybroker.entityprovider.capabilities.CollectionResolvable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Createable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Deleteable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Describeable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Inputable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Outputable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Resolvable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Statisticable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Updateable;
import org.sakaiproject.entitybroker.entityprovider.extension.ActionReturn;
import org.sakaiproject.entitybroker.entityprovider.extension.Formats;
import org.sakaiproject.entitybroker.entityprovider.search.Restriction;
import org.sakaiproject.entitybroker.entityprovider.search.Search;
import org.sakaiproject.entitybroker.exception.EntityException;
import org.sakaiproject.entitybroker.exception.EntityNotFoundException;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.entitybroker.util.AbstractEntityProvider;
import org.sakaiproject.exception.IdInvalidException;
import org.sakaiproject.exception.InconsistentException;
import org.sakaiproject.exception.IdUsedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.OverQuotaException;
import org.sakaiproject.exception.ServerOverloadException;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.exception.InUseException;
import org.sakaiproject.id.api.IdManager;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.util.ResourceLoader;
import org.sakaiproject.util.Validator;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * BBBMeetingEntityProvider is the EntityProvider class that implements several
 * EntityBroker capabilities.
 *
 * @author Adrian Fish, Nuno Fernandes
 */
@Slf4j
@Setter
public class BBBMeetingEntityProvider extends AbstractEntityProvider implements
        CoreEntityProvider, AutoRegisterEntityProvider, Inputable, Outputable,
        Createable, Updateable, Resolvable, Describeable, Deleteable,
        CollectionResolvable, ActionsExecutable, Statisticable {

    private BBBMeetingManager meetingManager;
    private UserDirectoryService userDirectoryService;
    private SiteService siteService;
    private IdManager idManager;
    private ServerConfigurationService serverConfigurationService;
    private ContentHostingService contentHostingService;
    private SecurityService securityService;
    private SessionManager sessionManager;

    public String[] getHandledOutputFormats() {
        return new String[] { Formats.HTML, Formats.JSON, Formats.TXT };
    }

    public String[] getHandledInputFormats() {
        return new String[] { Formats.HTML, Formats.JSON, Formats.FORM };
    }

    public String getEntityPrefix() {
        return BBBMeetingManager.ENTITY_PREFIX;
    }

    public Object getEntity(EntityReference ref) {

        log.debug("getEntity(" + ref.getId() + ")");

        String id = ref.getId();
        if (id == null || "".equals(id)) {
            return new BBBMeeting();
        }

        try {
            BBBMeeting meeting = meetingManager.getMeeting(id);

            if (meeting == null) {
                throw new EntityNotFoundException("Meeting not found", ref.getReference());
            }

            // for security reasons, clear passwords and meeting token
            meeting.setAttendeePassword(null);
            meeting.setModeratorPassword(null);

            return meeting;

        } catch (SecurityException se) {
            throw new EntityException(se.getMessage(), ref.getReference(), 400);
        } catch (Exception e) {
            throw new EntityException(e.getMessage(), ref.getReference(), 400);
        }
    }

    public boolean entityExists(String id) {

        log.debug("entityExists(" + id + ")");

        if (id == null || "".equals(id))
            return false;

        try {
            return (meetingManager.getMeeting(id) != null);
        } catch (SecurityException se) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // --- CRUDable
    // ------------------------------------------------------------------
    public Object getSampleEntity() {
        return new BBBMeeting();
    }

    public String createEntity(EntityReference ref, Object entity, Map<String, Object> params) {

        log.debug("createMeeting");

        // DEU güvenlik: CSRF jetonu
        checkCsrfToken(params);

        log.debug("EntityReference:" + ref.toString() + ", Entity:" + entity.toString() + ", params:" + params.toString());

        BBBMeeting meeting = (BBBMeeting) entity;

        // generate uuid
        meeting.setId(idManager.createUuid());

        // owner
        meeting.setOwnerId(userDirectoryService.getCurrentUser().getId());
        meeting.setOwnerDisplayName(userDirectoryService.getCurrentUser().getDisplayName());

        // recording flag
        String recordingStr = (String) params.get("recording");
        boolean recording = (recordingStr != null &&
                (recordingStr.toLowerCase().equals("on") || recordingStr.toLowerCase().equals("true")));
        meeting.setRecording(recording ? Boolean.TRUE : Boolean.FALSE);

        // waitForModerator flag
        String waitForModeratorStr = (String) params.get("waitForModerator");
        boolean waitForModerator = (waitForModeratorStr != null &&
                (waitForModeratorStr.toLowerCase().equals("on") || waitForModeratorStr.toLowerCase().equals("true")));
        meeting.setWaitForModerator(Boolean.valueOf(waitForModerator));

        // multipleSessionsAllowed flag
        String multipleSessionsAllowedStr = (String) params.get("multipleSessionsAllowed");
        boolean multipleSessionsAllowed = (multipleSessionsAllowedStr != null &&
                (multipleSessionsAllowedStr.toLowerCase().equals("on") || multipleSessionsAllowedStr.toLowerCase().equals("true")));
        meeting.setMultipleSessionsAllowed(Boolean.valueOf(multipleSessionsAllowed));

        //preuploaded presentation
        String presentationUrl = (String) params.get("presentation");
        meeting.setPresentation(presentationUrl);

        // groupSessions flag
        String groupSessionsStr = (String) params.get("groupSessions");
        boolean groupSessions = (groupSessionsStr != null &&
                (groupSessionsStr.toLowerCase().equals("on") || groupSessionsStr.toLowerCase().equals("true")));
        meeting.setGroupSessions(Boolean.valueOf(groupSessions));

        // participants
        String meetingOwnerId = meeting.getOwnerId();
        List<BBBMeetingParticipant> participants = extractParticipants(params, meetingOwnerId);
        participants.forEach(p -> p.setMeeting(meeting));
        meeting.setParticipants(participants);

        // store meeting
        String addToCalendarStr = (String) params.get("addToCalendar");
        String notifyParticipantsStr = (String) params.get("notifyParticipants");
        String iCalAttachedStr = (String) params.get("iCalAttached");
        String iCalAlarmMinutesStr = (String) params.get("iCalAlarmMinutes");
        boolean addToCalendar = addToCalendarStr != null
                && (addToCalendarStr.toLowerCase().equals("on") || addToCalendarStr.toLowerCase().equals("true"));
        boolean notifyParticipants = notifyParticipantsStr != null
                && (notifyParticipantsStr.toLowerCase().equals("on") || notifyParticipantsStr.toLowerCase().equals("true"));
        boolean iCalAttached = iCalAttachedStr != null
                && (iCalAttachedStr.toLowerCase().equals("on") || iCalAttachedStr.toLowerCase().equals("true"));
        Long iCalAlarmMinutes = iCalAlarmMinutesStr != null? Long.valueOf(iCalAlarmMinutesStr): 0L;

        // generate differentiated passwords
        meeting.setAttendeePassword(generatePassword());
        do {
            meeting.setModeratorPassword(generatePassword());
        } while (meeting.getAttendeePassword().equals(
                meeting.getModeratorPassword()));

        // generate voiceBidge
        String voiceBridgeStr = (String) params.get("voiceBridge");
        log.debug("voiceBridgeStr:" + voiceBridgeStr);
        if (voiceBridgeStr == null || voiceBridgeStr.equals("")
                || Integer.parseInt(voiceBridgeStr) == 0) {
            Integer voiceBridge = 70000 + new Random().nextInt(10000);
            meeting.setVoiceBridge(voiceBridge);
        } else {
            meeting.setVoiceBridge(Integer.valueOf(voiceBridgeStr));
        }

        try {
            if (!meetingManager.createMeeting(meeting, notifyParticipants, addToCalendar, iCalAttached, iCalAlarmMinutes))
                throw new EntityException("Unable to store meeting in DB", meeting.getId(), 400);
        } catch (BBBException e) {
            throw new EntityException(e.getPrettyMessage(), meeting.getId(), 400);
        }

        return meeting.getId();
    }

    public void updateEntity(EntityReference ref, Object entity, Map<String, Object> params) {

        log.debug("updateMeeting");

        // DEU güvenlik: CSRF jetonu
        checkCsrfToken(params);

        BBBMeeting newMeeting = (BBBMeeting) entity;

        try {
            BBBMeeting meeting = meetingManager.getMeeting(ref.getId());
            if (meeting == null) {
                throw new IllegalArgumentException("Could not locate meeting to update");
            }
            // update name
            String nameStr = (String) params.get("name");
            nameStr = StringEscapeUtils.escapeHtml(nameStr);
            if (nameStr != null) {
                meeting.setName(nameStr);
            }

            // update description
            String welcomeMessageStr = (String) params.get("properties.welcomeMessage");
            if (welcomeMessageStr != null) {
                meeting.getProperties().put("welcomeMessage", welcomeMessageStr);
            }

            // update recording flag
            String recordingStr = (String) params.get("recording");
            boolean recording = (recordingStr != null &&
                    (recordingStr.toLowerCase().equals("on") || recordingStr.toLowerCase().equals("true")));
            meeting.setRecording(Boolean.valueOf(recording));

            // update recordingDuration
            String recordingDurationStr = (String) params.get("recordingDuration");
            if (recordingDurationStr != null)
                meeting.setRecordingDuration(Long.valueOf(recordingDurationStr));
            else
                meeting.setRecordingDuration(0L);

            // update voiceBridge only if the voiceBridge parameter is sent from
            // the view to the controller
            String voiceBridgeStr = (String) params.get("voiceBridge");
            if (voiceBridgeStr != null) {
                if (voiceBridgeStr.equals("")
                        || Integer.parseInt(voiceBridgeStr) == 0) {
                    Integer voiceBridge = 70000 + new Random().nextInt(10000);
                    meeting.setVoiceBridge(voiceBridge);
                } else {
                    meeting.setVoiceBridge(Integer.valueOf(voiceBridgeStr));
                }
            }

            // update waitForModerator flag
            String waitForModeratorStr = (String) params.get("waitForModerator");
            boolean waitForModerator = (waitForModeratorStr != null &&
                    (waitForModeratorStr.toLowerCase().equals("on") || waitForModeratorStr.toLowerCase().equals("true")));
            meeting.setWaitForModerator(Boolean.valueOf(waitForModerator));

            // update multipleSessionsAllowed flag
            String multipleSessionsAllowedStr = (String) params.get("multipleSessionsAllowed");
            boolean multipleSessionsAllowed = (multipleSessionsAllowedStr != null &&
                    (multipleSessionsAllowedStr.toLowerCase().equals("on") || multipleSessionsAllowedStr.toLowerCase().equals("true")));
            meeting.setMultipleSessionsAllowed(Boolean.valueOf(multipleSessionsAllowed));

            // update default presentation if preuploadPresentation flag is true
            String presentationUrl = (String) params.get("presentation");
            if (presentationUrl != null && presentationUrl != "") {
                meeting.setPresentation(presentationUrl);
            } else {
                meeting.setPresentation("");
            }

            // update groupSessions flag
            String groupSessionsStr = (String) params.get("groupSessions");
            boolean groupSessions = (groupSessionsStr != null &&
                    (groupSessionsStr.toLowerCase().equals("on") || groupSessionsStr.toLowerCase().equals("true")));
            meeting.setGroupSessions(Boolean.valueOf(groupSessions));

            // update dates
            if (params.get("startDate") != null)
                meeting.setStartDate(newMeeting.getStartDate());
            else
                meeting.setStartDate(null);
            if (params.get("endDate") != null)
                meeting.setEndDate(newMeeting.getEndDate());
            else
                meeting.setEndDate(null);

            // update participants
            String meetingOwnerId = meeting.getOwnerId();
            List<BBBMeetingParticipant> participants = extractParticipants(params, meetingOwnerId);
            meeting.getParticipants().clear();
            meetingManager.updateMeeting(meeting, false, false, false, 0L, false);
            meeting = meetingManager.getMeeting(ref.getId());
            for (BBBMeetingParticipant p : participants) {
                p.setMeeting(meeting);
            }
            meeting.getParticipants().addAll(participants);

            // store meeting
            String addToCalendarStr = (String) params.get("addToCalendar");
            String notifyParticipantsStr = (String) params.get("notifyParticipants");
            String iCalAttachedStr = (String) params.get("iCalAttached");
            String iCalAlarmMinutesStr = (String) params.get("iCalAlarmMinutes");
            boolean addToCalendar = addToCalendarStr != null
                    && (addToCalendarStr.toLowerCase().equals("on") || addToCalendarStr.toLowerCase().equals("true"));
            boolean notifyParticipants = notifyParticipantsStr != null
                    && (notifyParticipantsStr.toLowerCase().equals("on") || notifyParticipantsStr.toLowerCase().equals("true"));
            boolean iCalAttached = iCalAttachedStr != null
                    && (iCalAttachedStr.toLowerCase().equals("on") || iCalAttachedStr.toLowerCase().equals("true"));
            Long iCalAlarmMinutes = iCalAlarmMinutesStr != null? Long.valueOf(iCalAlarmMinutesStr): 0L;

            try {
                if (!meetingManager.updateMeeting(meeting, notifyParticipants, addToCalendar, iCalAttached, iCalAlarmMinutes, false))
                    throw new EntityException("Unable to update meeting in DB", meeting.getId(), 400);
            } catch (BBBException e) {
                throw new EntityException(e.getPrettyMessage(), meeting.getId(), 400);
            }
        } catch (SecurityException se) {
            throw new EntityException(se.getMessage(), ref.getReference(), 400);
        } catch (Exception e) {
            throw new EntityException(e.getMessage(), ref.getReference(), 400);
        }
    }

    public List<BBBMeeting> getEntities(EntityReference ref, Search search) {

        log.debug("getEntities");

        List<BBBMeeting> meetings = null;

        Restriction locRes = search.getRestrictionByProperty(CollectionResolvable.SEARCH_LOCATION_REFERENCE);

        if (locRes != null) {
            String location = locRes.getStringValue();
            String context = null;

            if (location != null
                    && location.startsWith(SiteService.REFERENCE_ROOT)) {
                context = location.substring(SiteService.REFERENCE_ROOT.length() + 1);
            } else {
                context = location;
            }

            try {
                meetings = meetingManager.getSiteMeetings(context);

                // for security reasons, clear passwords and meeting token
                for (BBBMeeting meeting : meetings) {
                    meeting.setAttendeePassword(null);
                    meeting.setModeratorPassword(null);
                }

            } catch (Exception e) {
                throw new EntityException(e.getMessage(), ref.getReference(), 400);
            }

        } else {
            throw new IllegalArgumentException("Missing required parameter " + CollectionResolvable.SEARCH_LOCATION_REFERENCE);
        }

        return meetings;
    }

    public void deleteEntity(EntityReference ref, Map<String, Object> params) {

        log.debug("deleteEntity");

        if (ref == null) {
            throw new EntityNotFoundException("Meeting not found", null);
        }

        try {
            meetingManager.deleteMeeting(ref.getId());
        } catch (BBBException e) {
            throw new EntityException(e.getPrettyMessage(), ref.getReference(), 400);
        }
    }

    // --- ActionsExecutable (Custom actions)
    // ----------------------------------------
    @EntityCustomAction(viewKey = EntityView.VIEW_LIST)
    public ActionReturn getSettings(Map<String, Object> params) {

        log.debug("getSettings");

        Map<String, Object> settings = new LinkedHashMap<>();

        String siteId = params.containsKey("siteId")? (String) params.get("siteId"): null;
        User user = userDirectoryService.getCurrentUser();
        Map<String, Object> currentUser = getCurrentUser(user);
        currentUser.put("role", meetingManager.getUserRoleInSite((String)currentUser.get("id"), siteId));
        currentUser.put("permissions", getUserPermissionsInSite((String)currentUser.get("id"), siteId));
        settings.put("currentUser", currentUser);

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("autorefreshInterval", getAutorefreshInterval());
        config.put("addUpdateFormParameters", getAddUpdateFormConfigParameters());
        config.put("serverTimeInDefaultTimezone", meetingManager.getServerTimeInDefaultTimezone());
        config.put("serverTimeInUserTimezone", meetingManager.getServerTimeInUserTimezone());
        config.put("recordingFormatFilterEnabled", meetingManager.isRecordingFormatFilterEnabled());
        settings.put("config", config);
        settings.put("toolVersion", meetingManager.getToolVersion());
        return new ActionReturn(settings);
    }

    private Map<String, Object>getCurrentUser(User user) {

        Map<String, Object> currentUser = new LinkedHashMap<>();
        currentUser.put("id", user.getId());
        currentUser.put("displayId", user.getDisplayId());
        currentUser.put("displayName", user.getDisplayName());
        currentUser.put("eid", user.getEid());
        currentUser.put("email", user.getEmail());
        return currentUser;
    }

    private List<String> getUserPermissionsInSite(String userId, String siteId) {

        List<String> permissions = new ArrayList<>();
        if(meetingManager.isUserAllowedInLocation(userId, "site.viewRoster", siteId)) {
            permissions.add("site.viewRoster");
        }
        if(meetingManager.isUserAllowedInLocation(userId, "site.upd", siteId)) {
            permissions.add("site.upd");
            permissions.add(meetingManager.FN_ADMIN);
        }
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_CREATE, siteId) )
            permissions.add(meetingManager.FN_CREATE);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_EDIT_OWN, siteId) )
            permissions.add(meetingManager.FN_EDIT_OWN);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_EDIT_ANY, siteId) )
            permissions.add(meetingManager.FN_EDIT_ANY);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_DELETE_OWN, siteId) )
            permissions.add(meetingManager.FN_DELETE_OWN);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_DELETE_ANY, siteId) )
            permissions.add(meetingManager.FN_DELETE_ANY);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_PARTICIPATE, siteId) )
            permissions.add(meetingManager.FN_PARTICIPATE);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_RECORDING_VIEW, siteId) )
            permissions.add(meetingManager.FN_RECORDING_VIEW);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_RECORDING_EDIT_OWN, siteId) )
            permissions.add(meetingManager.FN_RECORDING_EDIT_OWN);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_RECORDING_EDIT_ANY, siteId) )
            permissions.add(meetingManager.FN_RECORDING_EDIT_ANY);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_RECORDING_DELETE_OWN, siteId) )
            permissions.add(meetingManager.FN_RECORDING_DELETE_OWN);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_RECORDING_DELETE_ANY, siteId) )
            permissions.add(meetingManager.FN_RECORDING_DELETE_ANY);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_RECORDING_EXTENDEDFORMATS_OWN, siteId) )
            permissions.add(meetingManager.FN_RECORDING_EXTENDEDFORMATS_OWN);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_RECORDING_EXTENDEDFORMATS_ANY, siteId) )
            permissions.add(meetingManager.FN_RECORDING_EXTENDEDFORMATS_ANY);
        if( meetingManager.isUserAllowedInLocation(userId, meetingManager.FN_RECORDING_DOWNLOAD, siteId) )
            permissions.add(meetingManager.FN_RECORDING_DOWNLOAD); 
        if( meetingManager.isUserAllowedInLocation(userId, "calendar.new", siteId) )
            permissions.add("calendar.new");
        if( meetingManager.isUserAllowedInLocation(userId, "calendar.revise.own", siteId) )
            permissions.add("calendar.revise.own");
        if( meetingManager.isUserAllowedInLocation(userId, "calendar.revise.any", siteId) )
            permissions.add("calendar.revise.any");
        if( meetingManager.isUserAllowedInLocation(userId, "calendar.delete.own", siteId) )
            permissions.add("calendar.delete.own");
        if( meetingManager.isUserAllowedInLocation(userId, "calendar.delete.any", siteId) )
            permissions.add("calendar.delete.any");
        return permissions;
    }

    private Map<String, String> getAutorefreshInterval() {

        Map<String, String> interval = new LinkedHashMap<>();
        String autorefreshMeetings = meetingManager.getAutorefreshForMeetings();
        if (autorefreshMeetings != null) {
            interval.put("meetings", autorefreshMeetings);
        }
        String autorefreshRecordings = meetingManager.getAutorefreshForRecordings();
        if (autorefreshRecordings != null) {
            interval.put("recordings", autorefreshRecordings);
        }
        return interval;
    }

    private Map<String, Object> getAddUpdateFormConfigParameters() {

        Map<String, Object> map = new LinkedHashMap<>();
        //UX settings for 'recording' checkbox
        Boolean recordingEnabled = Boolean.parseBoolean(meetingManager.isRecordingEnabled());
        if (recordingEnabled != null) {
            map.put("recordingEnabled", recordingEnabled);
        }
        Boolean recordingEditable = Boolean.parseBoolean(meetingManager.isRecordingEditable());
        if (recordingEditable != null) {
            map.put("recordingEditable", recordingEditable);
        }
        Boolean recordingDefault = Boolean.parseBoolean(meetingManager.getRecordingDefault());
        if (recordingDefault != null) {
            map.put("recordingDefault", recordingDefault);
        }
        //UX settings for 'duration' box
        Boolean durationEnabled = Boolean.parseBoolean(meetingManager.isDurationEnabled());
        if (durationEnabled != null) {
            map.put("durationEnabled", durationEnabled);
        }
        String durationDefault = meetingManager.getDurationDefault();
        if (durationDefault != null) {
            map.put("durationDefault", durationDefault);
        }
        //UX settings for 'wait moderator' box
        Boolean waitmoderatorEnabled = Boolean.parseBoolean(meetingManager.isWaitModeratorEnabled());
        if (waitmoderatorEnabled != null) {
            map.put("waitmoderatorEnabled", waitmoderatorEnabled);
        }
        Boolean waitmoderatorEditable = Boolean.parseBoolean(meetingManager.isWaitModeratorEditable());
        if (waitmoderatorEditable != null) {
            map.put("waitmoderatorEditable", waitmoderatorEditable);
        }
        Boolean waitmoderatorDefault = Boolean.parseBoolean(meetingManager.getWaitModeratorDefault());
        if (waitmoderatorDefault != null) {
            map.put("waitmoderatorDefault", waitmoderatorDefault);
        }
        //UX settings for 'multiple sessions allowed' box
        Boolean multiplesessionsallowedEnabled = Boolean.parseBoolean(meetingManager.isMultipleSessionsAllowedEnabled());
        if (multiplesessionsallowedEnabled != null) {
            map.put("multiplesessionsallowedEnabled", multiplesessionsallowedEnabled);
        }
        Boolean multiplesessionsallowedEditable = Boolean.parseBoolean(meetingManager.isMultipleSessionsAllowedEditable());
        if (multiplesessionsallowedEditable != null) {
            map.put("multiplesessionsallowedEditable", multiplesessionsallowedEditable);
        }
        Boolean multiplesessionsallowedDefault = Boolean.parseBoolean(meetingManager.getMultipleSessionsAllowedDefault());
        if (multiplesessionsallowedDefault != null) {
            map.put("multiplesessionsallowedDefault", multiplesessionsallowedDefault);
        }
        //UX settings for 'preupload presentation' box
        Boolean preuploadpresentationEnabled = Boolean.parseBoolean(meetingManager.isPreuploadPresentationEnabled());
        if (preuploadpresentationEnabled != null) {
            map.put("preuploadpresentationEnabled", preuploadpresentationEnabled);
        }
        //UX settings for 'group sessions' box
        Boolean groupsessionsEnabled = Boolean.parseBoolean(meetingManager.isGroupSessionsEnabled());
        if (groupsessionsEnabled != null) {
            map.put("groupsessionsEnabled", groupsessionsEnabled);
        }
        Boolean groupsessionsEditable = Boolean.parseBoolean(meetingManager.isGroupSessionsEditable());
        if (groupsessionsEditable != null) {
            map.put("groupsessionsEditable", groupsessionsEditable);
        }
        Boolean groupsessionsDefault = Boolean.parseBoolean(meetingManager.getGroupSessionsDefault());
        if (groupsessionsDefault != null) {
            map.put("groupsessionsDefault", groupsessionsDefault);
        }
        //UX settings for 'description' box
        String descriptionMaxLength = meetingManager.getMaxLengthForDescription();
        if (descriptionMaxLength != null) {
            map.put("descriptionMaxLength", descriptionMaxLength);
        }
        String descriptionType = meetingManager.getTextBoxTypeForDescription();
        if (descriptionType != null) {
            map.put("descriptionType", descriptionType);
        }
        return map;
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_LIST)
    public String isMeetingRunning(Map<String, Object> params) {

        log.debug("isMeetingRunning");

        String meetingID = getStringParam(params, "meetingID");
        if (meetingID == null) {
            throw new IllegalArgumentException("Missing required parameters meetingId");
        }
        // DEU güvenlik: yalnız toplantıya erişebilen kullanıcı
        getAccessibleMeeting(meetingID);

        try {
            return Boolean.toString(meetingManager.isMeetingRunning(meetingID));
        } catch (BBBException e) {
            String ref = Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX + Entity.SEPARATOR + meetingID;
            throw new EntityException(e.getPrettyMessage(), ref, 400);
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_NEW)
    public String endMeeting(Map<String, Object> params) {

        log.debug("endMeeting");

        // DEU güvenlik: durum değiştiren işlem yalnız POST + CSRF jetonuyla (izin denetimi BBBMeetingManager.endMeeting'de)
        requireLoggedInUser();
        checkCsrfToken(params);

        String meetingID = getStringParam(params, "meetingID");
        if (meetingID == null) {
            throw new IllegalArgumentException("Missing required parameter [meetingID]");
        }
        String groupId = getStringParam(params, "groupId");
        String endAll = getStringParam(params, "endAll");
        try {
            if (groupId != null) {
                return Boolean.toString(meetingManager.endMeeting(meetingID, groupId, false));
            } else if (endAll != null) {
                return Boolean.toString(meetingManager.endMeeting(meetingID, "", true));
            }

            return Boolean.toString(meetingManager.endMeeting(meetingID, "", false));
        } catch (SecurityException se) {
            throw new EntityException(se.getMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        } catch (BBBException e) {
            throw new EntityException(e.getPrettyMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 400);
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_SHOW)
    public ActionReturn getMeetingInfo(OutputStream out, EntityView view, EntityReference ref, Map<String, Object> params) {

        log.debug("getMeetingInfo");

        if (ref == null) {
            throw new EntityNotFoundException("Meeting not found", null);
        }

        // DEU güvenlik: toplantı bilgisi (katılımcı adları) yalnız toplantıya erişebilen kullanıcıya verilir
        getAccessibleMeeting(ref.getId());

        try {
            String groupId = getStringParam(params, "groupId");
            Map<String, Object> meetingInfoResponse = meetingManager.getMeetingInfo(ref.getId(), groupId);
            return new ActionReturn(meetingInfoResponse);
        } catch (BBBException e) {
            return new ActionReturn(new HashMap<String, String>());
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_SHOW)
    public ActionReturn getRecordings(EntityReference ref, Map<String, Object> params) {

        log.debug("getRecordings");

        if (ref == null) {
            throw new EntityNotFoundException("Meeting not found", null);
        }

        String groupId = (String) params.get("groupId");
        String siteId = (String) params.get("siteId");
        try {
            Map<String, Object> recordingsResponse = meetingManager.getRecordings(ref.getId(), groupId, siteId);
            return new ActionReturn(recordingsResponse);
        } catch (SecurityException se) {
            throw new EntityException(se.getMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        } catch (BBBException e) {
            return new ActionReturn(new HashMap<String, String>());
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_LIST)
    public ActionReturn getSiteRecordings(Map<String, Object> params) {

        log.debug("getSiteRecordings");

        String siteId = (String) params.get("siteId");

        if (!meetingManager.getCanViewSiteRecordings(siteId)) {
            throw new SecurityException("You are not allowed to view recordings");
        }

        try {
            Map<String, Object> recordingsResponse = meetingManager.getSiteRecordings(siteId);
            return new ActionReturn(recordingsResponse);
        } catch (Exception e) {
            log.error("Failed to retrieve site recordings", e);
            return new ActionReturn(new HashMap<String, String>());
        }
    }

    // DEU güvenlik: kayıt işlemleri yalnız POST + CSRF jetonuyla; meetingID zorunlu. İzin toplantının kendi
    // sitesinde denetlenir ve kaydın o toplantıya ait olduğu Scalelite'tan doğrulanır (BBBMeetingManagerImpl).
    @EntityCustomAction(viewKey = EntityView.VIEW_NEW)
    public String publishRecordings(Map<String, Object> params) {

        log.debug("publishRecordings");

        requireLoggedInUser();
        checkCsrfToken(params);
        String meetingID = getStringParam(params, "meetingID");
        String recordID = getStringParam(params, "recordID");
        String publish = getStringParam(params, "publish");
        if (meetingID == null || recordID == null || publish == null) {
            throw new IllegalArgumentException("Missing required parameters [meetingID, recordID, publish]");
        }

        try {
            return Boolean.toString(meetingManager.publishRecordings(meetingID, recordID, publish));
        } catch (SecurityException se) {
            throw new EntityException(se.getMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        } catch (BBBException e) {
            throw new EntityException(e.getPrettyMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 400);
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_NEW)
    public String protectRecordings(Map<String, Object> params) {

        log.debug("protectRecordings");

        requireLoggedInUser();
        checkCsrfToken(params);
        String meetingID = getStringParam(params, "meetingID");
        String recordID = getStringParam(params, "recordID");
        String protect = getStringParam(params, "protect");
        if (meetingID == null || recordID == null || protect == null) {
            throw new IllegalArgumentException("Missing required parameters [meetingID, recordID, protect]");
        }

        try {
            return Boolean.toString(meetingManager.protectRecordings(meetingID, recordID, protect));
        } catch (SecurityException se) {
            throw new EntityException(se.getMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        } catch (BBBException e) {
            throw new EntityException(e.getPrettyMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 400);
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_NEW)
    public String deleteRecordings(Map<String, Object> params) {

        log.debug("deleteRecordings");

        requireLoggedInUser();
        checkCsrfToken(params);
        String meetingID = getStringParam(params, "meetingID");
        String recordID = getStringParam(params, "recordID");
        if (meetingID == null || recordID == null) {
            throw new IllegalArgumentException("Missing required parameters [meetingID, recordID]");
        }

        try {
            return Boolean.toString(meetingManager.deleteRecordings(meetingID, recordID));
        } catch (SecurityException se) {
            throw new EntityException(se.getMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        } catch (BBBException e) {
            throw new EntityException(e.getPrettyMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 400);
        }
    }

    /**
     * DEU: İzle/İndir düğmesi için tıklama anında, izin denetlenerek tek kayda ait taze oynatma (ya da
     * download=true ise indirme) adresi döner. POST + CSRF: jetonlu adres başka siteden okunamaz.
     */
    @EntityCustomAction(viewKey = EntityView.VIEW_NEW)
    public String getRecordingUrl(Map<String, Object> params) {

        log.debug("getRecordingUrl");

        requireLoggedInUser();
        checkCsrfToken(params);
        String meetingID = getStringParam(params, "meetingID");
        String recordID = getStringParam(params, "recordID");
        String format = getStringParam(params, "format");
        boolean download = "true".equals(getStringParam(params, "download"));
        if (meetingID == null || recordID == null) {
            throw new IllegalArgumentException("Missing required parameters [meetingID, recordID]");
        }

        try {
            return meetingManager.getRecordingUrl(meetingID, recordID, format, download);
        } catch (SecurityException se) {
            throw new EntityException(se.getMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        } catch (BBBException e) {
            throw new EntityException(e.getPrettyMessage(), Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 400);
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_SHOW)
    public String getJoinMeetingUrl(OutputStream out, EntityView view, EntityReference ref) {

        log.debug("getJoinUrl");

        if (ref == null) {
            throw new EntityNotFoundException("Meeting not found", null);
        }

        try {
            User user = userDirectoryService.getCurrentUser();
            String meetingId = ref.getId();
            BBBMeeting meeting = meetingManager.getMeeting(meetingId);
            if (meeting == null) {
                throw new EntityException("This meeting is no longer available.", null, 404);
            }
            //Unescape Meeting name
            String nameStr = meeting.getName();
            meeting.setName(StringEscapeUtils.unescapeHtml(nameStr));

            String joinUrl = meetingManager.getJoinUrl(meeting, user);

            if (joinUrl == null) {
                throw new EntityException("You are not allowed to join this meeting.", meeting.getId(), 403);
            }

            try {
                meetingManager.checkJoinMeetingPreConditions(meeting);
            } catch (BBBException e) {
                throw new EntityException(e.getPrettyMessage(), meeting.getId(), 400);
            }

            // log meeting join event
            meetingManager.logMeetingJoin(ref.getId());
            return joinUrl;
        } catch (Exception e) {
            throw new EntityException(e.getMessage(), ref.getReference(), 400);
        }
        // pre-join meeting
    }

    public final static boolean WAITFORMODERATOR = true;
    public final static boolean NOTWAITFORMODERATOR = false;

    @EntityCustomAction(viewKey = EntityView.VIEW_SHOW)
    public String joinMeeting(OutputStream out, EntityView view, EntityReference ref, Map<String, Object> params) {

        log.debug("joinMeeting");

        if (ref == null) {
            throw new EntityNotFoundException("Meeting not found", null);
        }

        // get join url
        try {
            User user = userDirectoryService.getCurrentUser();
            String meetingId = ref.getId();
            BBBMeeting meeting = meetingManager.getMeeting(meetingId);
            if (meeting == null) {
                throw new EntityException("This meeting is no longer available.", null, 404);
            }

            //group sessions
            String groupId = getStringParam(params, "groupId");
            String groupTitle = null;
            if (groupId != null && meeting.getGroupSessions()) {
                // DEU güvenlik: grup toplantının sitesinde olmalı ve kullanıcı o grubun üyesi ya da toplantıyı
                // düzenleyebilen biri olmalı. Grup adı istemciden alınmaz, siteden okunur (sayfaya ve BBB'ye
                // istemci metni girmez).
                Group group = siteService.getSite(meeting.getSiteId()).getGroup(groupId);
                if (group == null) {
                    throw new EntityException("Invalid group", ref.getReference(), 400);
                }
                if (!meetingManager.getUserGroupIdsInSite(user.getId(), meeting.getSiteId()).contains(groupId)
                        && !meetingManager.getCanEdit(meeting.getSiteId(), meeting) && !securityService.isSuperUser()) {
                    log.warn("bbb-tool güvenlik: grup oturumuna katılım reddedildi, grup üyesi değil (kullanıcı={})", user.getId());
                    throw new EntityException("You are not a member of this group", ref.getReference(), 403);
                }
                groupTitle = group.getTitle();
                meeting.setId(meeting.getId() + "[" + groupId + "]");
            } else {
                groupId = null;
                meeting.setId(meeting.getId());
            }

            //Unescape Meeting name
            String nameStr = meeting.getName();
            if (groupTitle != null){
                meeting.setName(StringEscapeUtils.unescapeHtml(nameStr) + " (" + groupTitle + ")");
            } else {
                meeting.setName(StringEscapeUtils.unescapeHtml(nameStr));
            }

            String joinUrl = meetingManager.getJoinUrl(meeting, user);

            if (joinUrl == null) {
                throw new EntityException("You are not allowed to join this meeting.", meeting.getId(), 403);
            }

            // log meeting join event
            meetingManager.logMeetingJoin(meetingId);

            //Build the corresponding page for joining
            //If the user is not a moderator and WaitForModerator is enabled, do not create the meeting
            Boolean waitmoderatorEnabled = Boolean.parseBoolean(meetingManager.isWaitModeratorEnabled());
            if (waitmoderatorEnabled == null)
                waitmoderatorEnabled = true;
            String html;
            if( waitmoderatorEnabled && meeting.getWaitForModerator() ){
                BBBMeetingParticipant p = meetingManager.getParticipantFromMeeting(meeting, userDirectoryService.getCurrentUser().getId());
                if( !(Participant.MODERATOR).equals(p.getRole())) {
                    Map<String, Object> meetingInfo = null;
                    if(groupId != null && meeting.getGroupSessions())
                        meetingInfo = meetingManager.getMeetingInfo(meetingId, groupId);
                    else
                        meetingInfo = meetingManager.getMeetingInfo(meetingId, "");

                    if( meetingInfo == null || meetingInfo.isEmpty() || Integer.parseInt((String)meetingInfo.get("moderatorCount")) <= 0 ) {
                        //check for group session
                        if (groupId != null && meeting.getGroupSessions())
                            html = getHtmlForJoining(joinUrl, meetingId, WAITFORMODERATOR, groupId);
                        else
                            html = getHtmlForJoining(joinUrl, meetingId, WAITFORMODERATOR, "");
                        return html;
                    }
                }
            }
            //Else if the user is a moderator / WaitForModerator is not enabled, create the meeting
            try {
                meetingManager.checkJoinMeetingPreConditions(meeting);
            } catch (BBBException e) {
                throw new EntityException(e.getPrettyMessage(), meeting.getId(), 400);
            }

            //check for group session
            if (groupId != null && meeting.getGroupSessions())
                html = getHtmlForJoining(joinUrl, meetingId, NOTWAITFORMODERATOR, groupId);
            else
                html = getHtmlForJoining(joinUrl, meetingId);
            return html;

        } catch (EntityException ee) {
            throw ee;
        } catch (Exception e) {
            throw new EntityException(e.getMessage(), ref.getReference(), 400);
        }
        // pre-join meeting
    }

    private String getHtmlForJoining(String joinUrl, String meetingId){
        return getHtmlForJoining(joinUrl, meetingId, NOTWAITFORMODERATOR, "");
    }

    private String getHtmlForJoining(String joinUrl, String meetingId, boolean waitformoderator, String groupId){
        ResourceLoader toolMessages = new ResourceLoader("org.sakaiproject.bbb.bundle.ToolMessages");
        Locale locale = (new ResourceLoader()).getLocale();
        toolMessages.setContextLocale(locale);
        String waiting_for_moderator_tooltip = toolMessages.getString("bbb_meetinginfo_waiting_for_moderator_tooltip");

        String commonHtmlHeader = "<html>\n" +
                                  "  <head>\n" +
                                  "    <meta http-equiv='Content-Type' content='text/html; charset=UTF-8'/>\n" +
                                  "    <meta http-equiv='cache-control' content='max-age=0' />\n" +
                                  "    <meta http-equiv='cache-control' content='no-cache' />\n" +
                                  "    <meta http-equiv='expires' content='-1' />\n" +
                                  "    <meta http-equiv='expires' content='Tue, 01 Jan 1980 1:00:00 GMT' />\n" +
                                  "    <meta http-equiv='pragma' content='no-cache' />\n" +
                                  "    <title>BigBlueButton</title>\n" +
                                  "    <link rel='stylesheet' type='text/css' href='/library/skin/tool_base.css' />\n" +
                                  "    <link rel='stylesheet' type='text/css' href='/bbb-tool/css/bbb.css' />\n" +
                                  "    <script type='text/javascript' language='JavaScript' src='/bbb-tool/lib/jquery-1.3.2.min.js'></script>\n";
        String commonHtmlFooter = "</html>\n";

        if( waitformoderator ){
            return commonHtmlHeader +
                   "    <script type='text/javascript' language='JavaScript'>\n" +
                   "        (function worker() {\n" +
                   "            var meetingInfo;\n" +
                   "            // Disable caching of AJAX responses\n" +
                   "            // Validates if a moderator has joined\n" +
                   "            jQuery.ajax( {\n" +
                   "                url: '/direct/bbb-tool/" + meetingId + "/getMeetingInfo.json" + (groupId.equals("") ? "" : "?groupId=" + groupId) + "',\n" +
                   "                dataType : 'json',\n" +
                   "                async : false,\n" +
                   "                cache: false,\n" +
                   "                success : function(data) {\n" +
                   "                    meetingInfo = data;\n" +
                   "                    if (JSON.stringify(meetingInfo) === JSON.stringify({}))\n" +
                   "                        meetingInfo.moderatorCount = 0;\n" +
                   "                },\n" +
                   "                error : function(xmlHttpRequest, status, error) {\n" +
                   "                    return null;\n" +
                   "                },\n" +
                   "                complete : function() {\n" +
                   "                    if( parseInt(meetingInfo.moderatorCount) == 0 ){\n" +
                   "                        setTimeout(worker, 5000);\n" +
                   "                    } else {\n" +
                   "                        if (window.opener) {\n" +
                   "                           window.opener.setTimeout(\"meetings.utils.checkOneMeetingAvailability('" + meetingId + "'" + (groupId.equals("") ? "" : ", '" + groupId + "'") + ")\", 15000 );\n" +
                   "                        }\n" +
                   "                        window.location.reload();\n" +
                   "                    }\n" +
                   "                }\n" +
                   "            });\n" +
                   "        })();\n" +
                   "    </script>\n" +
                   "  </head>\n" +
                   "  <body>\n" +
                   "    <div align='center'>\n" +
                   "      <h3>Waiting for moderator to join the meeting</h3><br/><br/>\n" +
                   "      <img id='joining' src='/bbb-tool/images/2.gif' title='" + waiting_for_moderator_tooltip + "' alt='" + waiting_for_moderator_tooltip + "' />\n" +
                   "    </div>\n" +
                   "  </body>\n" +
                   commonHtmlFooter;
        } else {
            return commonHtmlHeader +
                   //"    <script type='text/javascript' language='JavaScript'>\n" +
                   //"        window.opener.setTimeout(\"meetings.utils.checkOneMeetingAvailability('" + meetingId + "'" + (groupId.equals("") ? "" : ", '" + groupId + "'") + ")\", 15000 );\n" +
                   //"    </script>\n" +
                   "    <meta http-equiv='refresh' content='0; url=" + joinUrl + "' />\n" +
                   "  </head>\n" +
                   "  <body>\n" +
                   "  </body>\n" +
                   commonHtmlFooter;
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_LIST)
    public ActionReturn getGroups(Map<String, Object> params) {

        log.debug("getGroups");

        String meetingID = (String) params.get("meetingID");
        if (meetingID == null) {
            throw new IllegalArgumentException("Missing required parameter [meetingID]");
        }

        // DEU güvenlik: yalnız toplantıya erişebilen kullanıcı (grup adları)
        BBBMeeting meeting = getAccessibleMeeting(meetingID);

        Site site;
        try {
            site = siteService.getSite(meeting.getSiteId());
        } catch (IdUnusedException e) {
            log.error("Unable to get groups in '" + meeting.getName() + "'.", e);
            return null;
        }

        //Get user's group ids
        List<String> groupIds = new ArrayList<String>();
        if (meetingManager.getCanEdit(meeting.getSiteId(), meeting)) {
            for(Group g : site.getGroups())
                groupIds.add(g.getId());
        } else {
            groupIds = meetingManager.getUserGroupIdsInSite(userDirectoryService.getCurrentUser().getId(), meeting.getSiteId());
        }

        Map<String, Object> groups = new HashMap<String, Object>();

        for(int i = 0; i < groupIds.size(); i++){
            Map<String, String> groupInfo = new HashMap<String, String>();
            Group group = site.getGroup(groupIds.get(i));

            groupInfo.put("groupId", groupIds.get(i));
            groupInfo.put("groupTitle", group.getTitle());
            groups.put("group" + i, groupInfo);
        }

        return new ActionReturn(groups);
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_LIST)
    public ActionReturn getUserSelectionOptions(Map<String, Object> params) {

        log.debug("getUserSelectionOptions");

        String siteId = getStringParam(params, "siteId");
        if (siteId == null) {
            throw new IllegalArgumentException("Missing required parameter siteId");
        }
        // DEU güvenlik: site üye listesi (ad, kullanıcı adı) yalnız o sitede toplantı açabilen ya da
        // düzenleyebilen kullanıcıya verilir (katılımcı seçme formu).
        String currentUserId = requireLoggedInUser();
        if (!canCreateOrEditMeetingsInSite(currentUserId, siteId)) {
            log.warn("bbb-tool güvenlik: site üye listesi reddedildi (kullanıcı={})", currentUserId);
            throw new EntityException("You are not allowed to list the members of this site", Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        }

        try {
            Map<String, Object> map = new HashMap<String, Object>();
            Site site = siteService.getSite(siteId);

            // groups
            List<Map<String, String>> groups = new ArrayList<Map<String, String>>();
            for (Group g : site.getGroups()) {
                Map<String, String> m = new HashMap<String, String>();
                m.put("id", g.getId());
                m.put("title", g.getTitle());
                groups.add(m);
            }
            map.put("groups", groups);

            // roles
            List<Map<String, String>> roles = new ArrayList<Map<String, String>>();
            for (Role r : site.getRoles()) {
                Map<String, String> m = new HashMap<String, String>();
                m.put("id", r.getId());
                m.put("title", r.getId());
                roles.add(m);
            }
            map.put("roles", roles);

            // users
            List<Map<String, String>> users = new ArrayList<Map<String, String>>();
            for (Member u : site.getMembers()) {
                String displayName = null;
                try {
                    displayName = userDirectoryService.getUser(u.getUserId())
                            .getDisplayName();
                } catch (UserNotDefinedException e1) {
                    log.warn("Could not retrieve displayName for userId: " + u.getUserId());
                }

                if (displayName != null) {
                    Map<String, String> m = new HashMap<String, String>();
                    m.put("id", u.getUserId());
                    m.put("title", displayName + " (" + u.getUserDisplayId() + ")");
                    users.add(m);
                }
            }
            map.put("users", users);

            // defaults
            Map<String, String> dlfts = new HashMap<String, String>();
            dlfts.put(BBBMeetingManager.CFG_DEFAULT_ALLUSERS,
                    serverConfigurationService.getString(BBBMeetingManager.CFG_DEFAULT_ALLUSERS, "true").toLowerCase());
            dlfts.put(BBBMeetingManager.CFG_DEFAULT_OWNER,
                    serverConfigurationService.getString(BBBMeetingManager.CFG_DEFAULT_OWNER, "moderator").toLowerCase());
            map.put("defaults", dlfts);

            return new ActionReturn(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_LIST)
    public ActionReturn getNoticeText(Map<String, Object> params) {

        log.debug("getNoticeText");

        Map<String, String> map = new HashMap<String, String>();
        String noticeText = meetingManager.getNoticeText();
        if (noticeText != null) {
            map.put("text", noticeText);
            map.put("level", meetingManager.getNoticeLevel());
        }
        return new ActionReturn(map);
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_NEW)
    public ActionReturn recordingReady(Map<String, Object> params) {
        String bbbSaltString = serverConfigurationService.getString(BBBMeetingManager.CFG_SALT);
        String bbbSalt = new String(Base64.encodeBase64(bbbSaltString.getBytes()));
        Claims claims = Jwts.parser().setSigningKey(bbbSalt).parseClaimsJws(params.get("signed_parameters").toString()).getBody();
        String meeting_id = claims.get("meeting_id").toString();

        boolean notified = meetingManager.recordingReady(meeting_id);

        ActionReturn response = null;
        if (notified) {
            response = new ActionReturn("OK");
            response.setResponseCode(200);
        } else {
            response = new ActionReturn("Gone");
            response.setResponseCode(410);
        }
        return response;
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_NEW)
    public String doUpload(Map<String, Object> params) {

        log.debug("Uploading File");

        // DEU güvenlik: yalnız oturum açmış, CSRF jetonu doğru ve o sitede toplantı açabilen/düzenleyebilen kullanıcı
        String userId = requireLoggedInUser();
        checkCsrfToken(params);
        String siteId = getStringParam(params, "siteId");
        if (siteId == null || !canCreateOrEditMeetingsInSite(userId, siteId)) {
            log.warn("bbb-tool güvenlik: sunum yükleme reddedildi (kullanıcı={})", userId);
            throw new EntityException("You are not allowed to upload a presentation to this site",
                    Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        }
        Object fileParam = params.get("file");
        if (!(fileParam instanceof FileItem)) {
            throw new IllegalArgumentException("Missing required parameter [file]");
        }
        FileItem file = (FileItem) fileParam;

        String url = "";
        try {
            String filename = Validator.getFileName(file.getName());
            String contentType = file.getContentType();

            InputStream fileContentStream = file.getInputStream();

            if (fileContentStream != null) {
                String name = Validator.getFileName(filename);
                String resourceId = Validator.escapeResourceName(name);

                ResourcePropertiesEdit props = contentHostingService.newResourceProperties();
                props.addProperty(ResourceProperties.PROP_DISPLAY_NAME, name);
                props.addProperty(ResourceProperties.PROP_DESCRIPTION, filename);
                SecurityAdvisor sa = uploadFileSecurityAdvisor();
                try {
                    ContentResource attachment = contentHostingService.addAttachmentResource(resourceId, siteId, "Meetings", contentType, fileContentStream, props);
                    // Make sure the resource is closed to public. DEU: danışman yalnız bu çağrı süresince etkin.
                    securityService.pushAdvisor(sa);
                    try {
                        contentHostingService.setPubView(attachment.getId(), false);
                    } finally {
                        securityService.popAdvisor(sa);
                    }
                    url = attachment.getUrl();
                } catch(IdInvalidException e) {
                    log.debug(e.getMessage());
                } catch(InconsistentException e) {
                    log.debug(e.getMessage());
                } catch(IdUsedException e) {
                    log.debug(e.getMessage());
                } catch(PermissionException e) {
                    log.debug(e.getMessage());
                } catch(OverQuotaException e) {
                    log.debug(e.getMessage());
                } catch(ServerOverloadException e) {
                    log.debug(e.getMessage());
                }
            }
        } catch(IOException e) {
            log.debug("Failed to upload file");
            log.debug(e.getMessage());
        }
        return url;
    }

    private SecurityAdvisor uploadFileSecurityAdvisor() {
        return (userId, function, reference) -> {
            //Needed to be able to add or modify their own
            if (function.equals(contentHostingService.AUTH_RESOURCE_ADD) ||
              function.equals(contentHostingService.AUTH_RESOURCE_WRITE_OWN) ||
              function.equals(contentHostingService.AUTH_RESOURCE_HIDDEN) ) {
                return SecurityAdvisor.SecurityAdvice.ALLOWED;
            } else if (function.equals(contentHostingService.AUTH_RESOURCE_WRITE_ANY)) {
                log.info(userId + " requested ability to write to any content on " + reference +
                    " which we didn't expect, this should be investigated");
                return SecurityAdvisor.SecurityAdvice.ALLOWED;
            }
			      return SecurityAdvisor.SecurityAdvice.PASS;
        };
    }

    @EntityCustomAction(viewKey = EntityView.VIEW_NEW)
    public String removeUpload(Map<String, Object> params) {

        log.debug("Removing File");

        // DEU güvenlik: önceki sürüm "content.delete.any" iznini koşulsuz veriyordu; herhangi bir kullanıcı Sakai'deki
        // herhangi bir dosyayı silebiliyordu. Artık yalnız bu aracın bir sitenin "Meetings" ek klasörüne yüklediği dosya,
        // o sitede toplantıyı düzenleyebilen (yeni toplantıda: toplantı açabilen) ve dosyayı kendisi yüklemiş (ya da
        // sitedeki bütün toplantıları düzenleyebilen) kullanıcı tarafından silinebilir.
        String userId = requireLoggedInUser();
        checkCsrfToken(params);
        String resourceId = getStringParam(params, "url");
        String meetingId = getStringParam(params, "meetingId");

        String siteId = getMeetingsAttachmentSiteId(resourceId);
        if (siteId == null) {
            log.warn("bbb-tool güvenlik: sunum silme reddedildi, geçersiz kaynak (kullanıcı={})", userId);
            throw new EntityException("Invalid presentation", Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        }

        BBBMeeting meeting = null;
        if (meetingId != null && !meetingId.isEmpty()) {
            try {
                meeting = meetingManager.getMeeting(meetingId);
            } catch (Exception e) {
                meeting = null;
            }
            if (meeting == null || !siteId.equals(meeting.getSiteId())
                    || !meetingManager.getCanEdit(meeting.getSiteId(), meeting)) {
                log.warn("bbb-tool güvenlik: sunum silme reddedildi, toplantı düzenleme izni yok (kullanıcı={})", userId);
                throw new EntityException("You are not allowed to change this meeting",
                        Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
            }
        } else if (!canCreateOrEditMeetingsInSite(userId, siteId)) {
            log.warn("bbb-tool güvenlik: sunum silme reddedildi, sitede toplantı izni yok (kullanıcı={})", userId);
            throw new EntityException("You are not allowed to remove this presentation",
                    Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        }

        SecurityAdvisor sa = removeUploadSecurityAdvisor(contentHostingService.getReference(resourceId));
        securityService.pushAdvisor(sa);
        try {
            if (!meetingManager.isUserAllowedInLocation(userId, BBBMeetingManager.FN_EDIT_ANY, siteId)) {
                String creator = contentHostingService.getProperties(resourceId).getProperty(ResourceProperties.PROP_CREATOR);
                if (!userId.equals(creator)) {
                    log.warn("bbb-tool güvenlik: sunum silme reddedildi, dosya başka kullanıcının (kullanıcı={})", userId);
                    return Boolean.toString(false);
                }
            }
            contentHostingService.removeResource(resourceId);
        } catch (PermissionException | IdUnusedException | TypeException | InUseException e) {
            log.debug(e.getMessage());
            return Boolean.toString(false);
        } finally {
            securityService.popAdvisor(sa);
        }

        if (meeting != null) {
            meeting.setPresentation("");
            try {
                //Update meeting presentation value
                if (!meetingManager.updateMeeting(meeting, false, false, false, 0L, true)) {
                    return Boolean.toString(false);
                }
            } catch (Exception e) {
                log.debug(e.getMessage());
                return Boolean.toString(false);
            }
        }
        return Boolean.toString(true);
    }

    /** DEU güvenlik: yalnız denetlenmiş tek dosya (tam başvuru) için okuma ve silme izni. */
    private SecurityAdvisor removeUploadSecurityAdvisor(final String allowedReference) {
        return (userId, function, reference) -> {
            if (allowedReference != null && allowedReference.equals(reference)
                    && (function.equals(ContentHostingService.AUTH_RESOURCE_READ)
                        || function.equals(ContentHostingService.AUTH_RESOURCE_REMOVE_OWN)
                        || function.equals(ContentHostingService.AUTH_RESOURCE_REMOVE_ANY)
                        || function.equals(ContentHostingService.AUTH_RESOURCE_HIDDEN))) {
                return SecurityAdvisor.SecurityAdvice.ALLOWED;
            }
            return SecurityAdvisor.SecurityAdvice.PASS;
        };
    }

    // --- DEU güvenlik yardımcıları
    // -------------------------------------------------------------
    private static final String CSRF_PARAM = "sakai_csrf_token";
    private static final Pattern MEETINGS_ATTACHMENT_ID = Pattern.compile("^/attachment/([^/]+)/Meetings/[^/]+/[^/]+$");

    private String requireLoggedInUser() {
        String userId = sessionManager.getCurrentSessionUserId();
        if (userId == null || userId.trim().isEmpty()) {
            throw new EntityException("You must be logged in", Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 401);
        }
        return userId;
    }

    /** Sakai CSRF jetonu (oturum özniteliği sakai.csrf.token) istekteki sakai_csrf_token ile sabit sürede karşılaştırılır. */
    private void checkCsrfToken(Map<String, Object> params) {
        Session session = sessionManager.getCurrentSession();
        Object expected = session != null ? session.getAttribute(UsageSessionService.SAKAI_CSRF_SESSION_ATTRIBUTE) : null;
        Object supplied = params != null ? params.get(CSRF_PARAM) : null;
        if (!(expected instanceof String) || !(supplied instanceof String)
                || !MessageDigest.isEqual(((String) expected).getBytes(StandardCharsets.UTF_8),
                        ((String) supplied).getBytes(StandardCharsets.UTF_8))) {
            log.warn("bbb-tool güvenlik: CSRF jetonu doğrulanamadı (kullanıcı={})", session != null ? session.getUserId() : null);
            throw new EntityException("CSRF token validation failed", Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        }
    }

    private static String getStringParam(Map<String, Object> params, String name) {
        Object value = params != null ? params.get(name) : null;
        return value instanceof String ? (String) value : null;
    }

    private boolean canCreateOrEditMeetingsInSite(String userId, String siteId) {
        return meetingManager.getCanCreate(siteId)
                || meetingManager.isUserAllowedInLocation(userId, BBBMeetingManager.FN_EDIT_OWN, siteId)
                || meetingManager.isUserAllowedInLocation(userId, BBBMeetingManager.FN_EDIT_ANY, siteId);
    }

    /** Toplantıya erişim denetimi: BBBMeetingManager.getMeeting katılımcı ya da yönetici olmayana null döner. */
    private BBBMeeting getAccessibleMeeting(String meetingId) {
        BBBMeeting meeting = null;
        try {
            meeting = meetingManager.getMeeting(meetingId);
        } catch (Exception e) {
            log.debug("getAccessibleMeeting: {}", e.getMessage());
        }
        if (meeting == null) {
            throw new EntityException("You are not allowed to access this meeting",
                    Entity.SEPARATOR + BBBMeetingManager.ENTITY_PREFIX, 403);
        }
        return meeting;
    }

    /** /attachment/<site>/Meetings/<klasör>/<ad> biçimindeki kaynak kimliğinden site kimliğini döner; değilse null. */
    private static String getMeetingsAttachmentSiteId(String resourceId) {
        if (resourceId == null || resourceId.contains("..") || resourceId.contains("%") || resourceId.contains("\\")) {
            return null;
        }
        java.util.regex.Matcher m = MEETINGS_ATTACHMENT_ID.matcher(resourceId);
        return m.matches() ? m.group(1) : null;
    }

    // --- Statisticable
    // -------------------------------------------------------------
    public String getAssociatedToolId() {
        return BBBMeetingManager.TOOL_ID;
    }

    public String[] getEventKeys() {
        return BBBMeetingManager.EVENT_KEYS;
    }

    public Map<String, String> getEventNames(Locale locale) {
        Map<String, String> localeEventNames = new HashMap<String, String>();
        ResourceLoader msgs = new ResourceLoader("org.sakaiproject.bbb.bundle.Events");
        msgs.setContextLocale(locale);
        for (int i = 0; i < BBBMeetingManager.EVENT_KEYS.length; i++) {
            localeEventNames.put(BBBMeetingManager.EVENT_KEYS[i], msgs.getString(BBBMeetingManager.EVENT_KEYS[i]));
        }
        return localeEventNames;
    }

    // --- UTILITY METHODS
    // -----------------------------------------------------------
    private List<BBBMeetingParticipant> extractParticipants(Map<String, Object> params,
            String meetingOwnerId) {

        List<BBBMeetingParticipant> participants = new ArrayList<>();
        for (String key : params.keySet()) {
            String selectionType = null;
            String selectionId = null;
            String role = null;

            if (key.startsWith("all_")) {
                selectionType = Participant.SELECTION_ALL;
                selectionId = Participant.SELECTION_ALL;
                role = (String) params.get("all-role_" + params.get(key));

            } else if (key.startsWith("group_")) {
                selectionType = Participant.SELECTION_GROUP;
                selectionId = (String) params.get(key);
                role = (String) params.get("group-role_" + selectionId);

            } else if (key.startsWith("role_")) {
                selectionType = Participant.SELECTION_ROLE;
                selectionId = (String) params.get(key);
                role = (String) params.get("role-role_" + selectionId);

            } else if (key.startsWith("user_")) {
                selectionType = Participant.SELECTION_USER;
                selectionId = (String) params.get(key);
                role = (String) params.get("user-role_" + selectionId);
            }

            if (selectionType != null && selectionId != null && role != null) {
                // DEU GÜVENLİK KISITLAMASI:
                // Tüm site üyeleri (all), Student rolü ve öğrenci hesapları oda açılırken moderatör yapılamaz; her zaman katılımcı (attendee) olarak kaydedilir.
                if (Participant.SELECTION_ALL.equals(selectionType)) {
                    role = Participant.ATTENDEE;
                } else if (Participant.SELECTION_ROLE.equals(selectionType) && "Student".equalsIgnoreCase(selectionId)) {
                    role = Participant.ATTENDEE;
                } else if (Participant.SELECTION_USER.equals(selectionType)) {
                    if (selectionId.contains("@ogr.deu.edu.tr") || selectionId.matches("^\\d{9,11}$")) {
                        role = Participant.ATTENDEE;
                    }
                }

                BBBMeetingParticipant p = new BBBMeetingParticipant();
                p.setSelectionType(selectionType);
                p.setSelectionId(selectionId);
                p.setRole(role);
                participants.add(p);
                //participants.add(new Participant(selectionType, selectionId,role));
            }
        }
        return participants;
    }

    /** Generate a random password */
    private String generatePassword() {
        return Long.toHexString(new Random(System.currentTimeMillis()).nextLong());
    }
}
