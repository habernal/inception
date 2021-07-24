/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.experimental.api;

import static de.tudarmstadt.ukp.clarin.webanno.api.annotation.coloring.Palette.PALETTE_NORMAL_FILTERED;
import static de.tudarmstadt.ukp.clarin.webanno.api.annotation.util.WebAnnoCasUtil.selectAnnotationByAddr;
import static de.tudarmstadt.ukp.clarin.webanno.api.annotation.util.WebAnnoCasUtil.selectFsByAddr;
import static java.lang.Math.toIntExact;
import static org.apache.uima.fit.util.CasUtil.getType;
import static org.apache.uima.fit.util.CasUtil.selectAllFS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.text.AnnotationFS;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.clarin.webanno.api.DocumentService;
import de.tudarmstadt.ukp.clarin.webanno.api.ProjectService;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.adapter.RelationAdapter;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.adapter.SpanAdapter;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.adapter.TypeAdapter;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.coloring.ColoringService;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.event.*;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.model.VID;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.preferences.UserPreferencesService;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.util.WebAnnoCasUtil;
import de.tudarmstadt.ukp.clarin.webanno.api.config.RepositoryProperties;
import de.tudarmstadt.ukp.clarin.webanno.api.dao.casstorage.CasStorageSession;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.support.logging.Logging;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.NewDocumentRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.NewViewportRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.relation.CreateRelationRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.relation.DeleteRelationRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.relation.SelectRelationRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.relation.UpdateRelationRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.span.CreateSpanRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.span.DeleteSpanRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.span.SelectSpanRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.request.span.UpdateSpanRequest;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.ErrorMessage;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.NewDocumentResponse;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.NewViewportResponse;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.relation.CreateRelationResponse;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.relation.DeleteRelationResponse;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.relation.SelectRelationResponse;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.span.CreateSpanResponse;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.span.DeleteSpanResponse;
import de.tudarmstadt.ukp.inception.experimental.api.messages.response.span.SelectSpanResponse;
import de.tudarmstadt.ukp.inception.experimental.api.model.Relation;
import de.tudarmstadt.ukp.inception.experimental.api.model.Span;
import de.tudarmstadt.ukp.inception.experimental.api.websocket.AnnotationProcessAPI;

@Component
@ConditionalOnProperty(prefix = "websocket", name = "enabled", havingValue = "true")
public class AnnotationSystemAPIImpl
    implements AnnotationSystemAPI
{
    private final AnnotationSchemaService annotationService;
    private final ProjectService projectService;
    private final DocumentService documentService;
    private final UserDao userDao;
    private final RepositoryProperties repositoryProperties;
    private final AnnotationProcessAPI annotationProcessAPI;
    private final AnnotationSystemAPIService annotationSystemAPIService;
    private final ColoringService coloringService;
    private final UserPreferencesService userPreferencesService;

    public AnnotationSystemAPIImpl(ProjectService aProjectService, DocumentService aDocumentService,
            UserDao aUserDao, RepositoryProperties aRepositoryProperties,
            AnnotationProcessAPI aAnnotationProcessAPI,
            AnnotationSchemaService aAnnotationSchemaService,
            AnnotationSystemAPIService aAnnotationSystemAPIService,
            ColoringService aColoringService, UserPreferencesService aUserPreferencesService)
    {
        projectService = aProjectService;
        documentService = aDocumentService;
        userDao = aUserDao;
        repositoryProperties = aRepositoryProperties;
        annotationProcessAPI = aAnnotationProcessAPI;
        annotationService = aAnnotationSchemaService;
        annotationSystemAPIService = aAnnotationSystemAPIService;
        coloringService = aColoringService;
        userPreferencesService = aUserPreferencesService;
    }

    @Override
    public void handleNewDocument(NewDocumentRequest aNewDocumentRequest) throws IOException
    {
        try {
            CAS cas = getCasForDocument(aNewDocumentRequest.getUserName(),
                    aNewDocumentRequest.getProjectId(), aNewDocumentRequest.getDocumentId());

            NewDocumentResponse message = new NewDocumentResponse(
                    toIntExact(documentService.getSourceDocument(aNewDocumentRequest.getProjectId(),
                            aNewDocumentRequest.getDocumentId()).getId()),
                    getViewportText(cas, aNewDocumentRequest.getViewport()),
                    filterSpans(getSpans(cas, aNewDocumentRequest.getProjectId()),
                            aNewDocumentRequest.getViewport()),
                    getRelations(cas, aNewDocumentRequest.getProjectId(),
                            aNewDocumentRequest.getViewport()));
            annotationProcessAPI.sendNewDocumentResponse(message,
                    aNewDocumentRequest.getClientName());
        }
        catch (Exception e) {
            e.printStackTrace();
            createErrorMessage(e.getMessage(), aNewDocumentRequest.getClientName());
        }

    }

    @Override
    public void handleNewViewport(NewViewportRequest aNewViewportRequest) throws IOException
    {
        CAS cas = getCasForDocument(aNewViewportRequest.getUserName(),
                aNewViewportRequest.getProjectId(), aNewViewportRequest.getDocumentId());

        NewViewportResponse message = new NewViewportResponse(
                getViewportText(cas, aNewViewportRequest.getViewport()),
                filterSpans(getSpans(cas, aNewViewportRequest.getProjectId()),
                        aNewViewportRequest.getViewport()),
                getRelations(cas, aNewViewportRequest.getProjectId(),
                        aNewViewportRequest.getViewport()));

        annotationProcessAPI.sendNewViewportResponse(message, aNewViewportRequest.getClientName());
    }

    @Override
    public void handleSelectSpan(SelectSpanRequest aSelectSpanRequest) throws IOException
    {
        CAS cas = getCasForDocument(aSelectSpanRequest.getUserName(),
                aSelectSpanRequest.getProjectId(), aSelectSpanRequest.getDocumentId());
        AnnotationFS span = selectAnnotationByAddr(cas,
                aSelectSpanRequest.getSpanAddress().getId());

        SelectSpanResponse message = new SelectSpanResponse(
                VID.parse(String.valueOf(span.getAddress())), span.getType().getShortName(),
                getFeatureForSpan(span));

        annotationProcessAPI.sendSelectAnnotationResponse(message,
                aSelectSpanRequest.getClientName());
    }

    @Override
    public void handleUpdateSpan(UpdateSpanRequest aUpdateSpanRequest)
    {
        try {
            CAS cas = getCasForDocument(aUpdateSpanRequest.getUserName(),
                    aUpdateSpanRequest.getProjectId(), aUpdateSpanRequest.getDocumentId());

            AnnotationFS annotation = selectAnnotationByAddr(cas,
                    aUpdateSpanRequest.getSpanAddress().getId());
            // TODO update feature
        }
        catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public void handleCreateSpan(CreateSpanRequest aCreateSpanRequest) throws IOException
    {
        try (CasStorageSession session = CasStorageSession.open()) {

            MDC.put(Logging.KEY_REPOSITORY_PATH, repositoryProperties.getPath().toString());
            SourceDocument sourceDocument = documentService.getSourceDocument(
                    aCreateSpanRequest.getProjectId(), aCreateSpanRequest.getDocumentId());

            CAS cas = documentService.readAnnotationCas(sourceDocument,
                    aCreateSpanRequest.getUserName());
            String layer = null;
            for (AnnotationLayer a : annotationService.listAnnotationLayer(
                    projectService.getProject(aCreateSpanRequest.getProjectId()))) {
                if (a.getName().contains(aCreateSpanRequest.getType())) {
                    layer = a.getName();
                }
                System.out.println(a.getName());
            }

            TypeAdapter adapter = annotationService.getAdapter(annotationService.findLayer(
                    projectService.getProject(aCreateSpanRequest.getProjectId()), layer));

            ((SpanAdapter) adapter).add(
                    documentService.getSourceDocument(aCreateSpanRequest.getProjectId(),
                            aCreateSpanRequest.getDocumentId()),
                    aCreateSpanRequest.getUserName(), cas, aCreateSpanRequest.getBegin(),
                    aCreateSpanRequest.getEnd());
        }
        catch (Exception e) {
            e.printStackTrace();
            createErrorMessage(e.getMessage(), aCreateSpanRequest.getClientName());
        }
    }

    @Override
    public void handleDeleteSpan(DeleteSpanRequest aDeleteSpanRequest) throws IOException
    {
        try (CasStorageSession session = CasStorageSession.open()) {
            MDC.put(Logging.KEY_REPOSITORY_PATH, repositoryProperties.getPath().toString());
            SourceDocument sourceDocument = documentService.getSourceDocument(
                    aDeleteSpanRequest.getProjectId(), aDeleteSpanRequest.getDocumentId());

            CAS cas = documentService.readAnnotationCas(sourceDocument,
                    aDeleteSpanRequest.getUserName());

            AnnotationFS annotation = selectAnnotationByAddr(cas,
                    aDeleteSpanRequest.getSpanAddress().getId());
            TypeAdapter adapter = annotationService.getAdapter(annotationService.findLayer(
                    projectService.getProject(aDeleteSpanRequest.getProjectId()),
                    getType(cas, annotation.getType().getName()).getName()));

            adapter.delete(
                    documentService.getSourceDocument(aDeleteSpanRequest.getProjectId(),
                            aDeleteSpanRequest.getDocumentId()),
                    aDeleteSpanRequest.getUserName(), cas,
                    VID.parse(String.valueOf(aDeleteSpanRequest.getSpanAddress())));
        }
        catch (Exception e) {
            e.printStackTrace();
            createErrorMessage(e.getMessage(), aDeleteSpanRequest.getClientName());
        }
    }

    @Override
    public void handleSelectRelation(SelectRelationRequest aSelectRelationRequest)
        throws IOException
    {
        try (CasStorageSession session = CasStorageSession.open()) {
            MDC.put(Logging.KEY_REPOSITORY_PATH, repositoryProperties.getPath().toString());
            SourceDocument sourceDocument = documentService.getSourceDocument(
                    aSelectRelationRequest.getProjectId(), aSelectRelationRequest.getDocumentId());

            CAS cas = documentService.readAnnotationCas(sourceDocument,
                    aSelectRelationRequest.getUserName());

            FeatureStructure relation = selectFsByAddr(cas,
                    aSelectRelationRequest.getRelationAddress().getId());

            //TODO combine with getRelations and simplify
            
            String flavor = null;
            String dependencyType = null;
            FeatureStructure governor = null;
            FeatureStructure dependent = null;
            String governorCoveredText = null;
            String dependentCoveredText = null;
            
            for (Feature f : relation.getType().getFeatures()) {

                if (f.getName().equals(
                    "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:Governor")) {
                    governor = relation.getFeatureValue(f);
                }
                if (f.getName().equals(
                    "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:Dependent")) {
                    dependent = relation.getFeatureValue(f);
                }
                if (f.getName().equals(
                    "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:DependencyType")) {
                    dependencyType = relation.getStringValue(f);
                }
                if (f.getName().equals(
                    "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:flavor")) {
                    flavor = relation.getStringValue(f);
                }
            }

            List<Feature> features = governor.getType().getFeatures();
            for (int i = 3; i < 8; i++) {
                if (governor.getFeatureValue(features.get(i)) != null) {
                    AnnotationFS govAnnotation = WebAnnoCasUtil.selectAnnotationByAddr(cas,
                        WebAnnoCasUtil.getAddr(governor));
                    governorCoveredText = govAnnotation.getCoveredText();
                    break;
                }
            }
            features.clear();
            features = dependent.getType().getFeatures();
            
            for (int i = 3; i < 8; i++) {
                if (dependent.getFeatureValue(features.get(i)) != null) {
                    AnnotationFS depAnnotation = WebAnnoCasUtil.selectAnnotationByAddr(cas,
                        WebAnnoCasUtil.getAddr(dependent));
                    dependentCoveredText = depAnnotation.getCoveredText();
                    break;
                }
            }

            SelectRelationResponse message = new SelectRelationResponse(
                    VID.parse(String.valueOf(relation._id())), governorCoveredText, dependentCoveredText,
                    dependencyType,
                    flavor);

            annotationProcessAPI.sendSelectRelationResponse(message,
                    aSelectRelationRequest.getClientName());
        }
        catch (Exception e) {
            e.printStackTrace();
            createErrorMessage(e.getMessage(), aSelectRelationRequest.getClientName());
        }
    }

    @Override
    public void handleUpdateRelation(UpdateRelationRequest aUpdateRelationRequest)
        throws IOException
    {
        try (CasStorageSession session = CasStorageSession.open()) {
            // TODO
        }
        catch (Exception e) {
            e.printStackTrace();
            createErrorMessage(e.getMessage(), aUpdateRelationRequest.getClientName());
        }
    }

    @Override
    public void handleCreateRelation(CreateRelationRequest aCreateRelationRequest)
        throws IOException
    {
        try (CasStorageSession session = CasStorageSession.open()) {

            MDC.put(Logging.KEY_REPOSITORY_PATH, repositoryProperties.getPath().toString());
            SourceDocument sourceDocument = documentService.getSourceDocument(
                    aCreateRelationRequest.getProjectId(), aCreateRelationRequest.getDocumentId());

            CAS cas = documentService.readAnnotationCas(sourceDocument,
                    aCreateRelationRequest.getUserName());

            AnnotationFS governor = selectAnnotationByAddr(cas,
                    aCreateRelationRequest.getGovernorId().getId());
            AnnotationFS dependent = selectAnnotationByAddr(cas,
                    aCreateRelationRequest.getDependentId().getId());

            TypeAdapter adapter = annotationService.getAdapter(annotationService.findLayer(
                    projectService.getProject(aCreateRelationRequest.getProjectId()),
                    getType(cas,
                            "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency")
                                    .getName()));

            ((RelationAdapter) adapter).add(
                    documentService.getSourceDocument(aCreateRelationRequest.getProjectId(),
                            aCreateRelationRequest.getDocumentId()),
                    aCreateRelationRequest.getUserName(), governor, dependent, cas);
        }
        catch (Exception e) {
            e.printStackTrace();
            createErrorMessage(e.getMessage(), aCreateRelationRequest.getClientName());
        }
    }

    @Override
    public void handleDeleteRelation(DeleteRelationRequest aDeleteRelationRequest)
        throws IOException
    {
        try (CasStorageSession session = CasStorageSession.open()) {
            MDC.put(Logging.KEY_REPOSITORY_PATH, repositoryProperties.getPath().toString());
            SourceDocument sourceDocument = documentService.getSourceDocument(
                    aDeleteRelationRequest.getProjectId(), aDeleteRelationRequest.getDocumentId());

            CAS cas = documentService.readAnnotationCas(sourceDocument,
                    aDeleteRelationRequest.getUserName());

            FeatureStructure relation = selectFsByAddr(cas,
                    aDeleteRelationRequest.getRelationAddress().getId());

            TypeAdapter adapter = annotationService.getAdapter(annotationService.findLayer(
                    projectService.getProject(aDeleteRelationRequest.getProjectId()),
                    getType(cas, relation.getType().getName()).getName()));

            adapter.delete(
                    documentService.getSourceDocument(aDeleteRelationRequest.getProjectId(),
                            aDeleteRelationRequest.getDocumentId()),
                    aDeleteRelationRequest.getUserName(), cas,
                    VID.parse(String.valueOf(aDeleteRelationRequest.getRelationAddress())));
        }
        catch (Exception e) {
            e.printStackTrace();
            createErrorMessage(e.getMessage(), aDeleteRelationRequest.getClientName());
        }
    }

    @Override
    public void onFeatureUpdatedEventHandler(FeatureValueUpdatedEvent aEvent)
    {
        System.out.println("FEATURE UPDATED EVENT: ");
        System.out.println(aEvent);
        // TODO Check What event that really is
        /*
         * UpdateSpanResponse response = new UpdateSpanResponse(
         * VID.parse(String.valueOf(aEvent.getFS().getAddress())), aEvent.getNewValue().toString(),
         * "#888888");
         * annotationProcessAPI.sendUpdateAnnotationResponse(response,aEvent.getProject().getId(),
         * aEvent.getDocument().getId(), aEvent.get);
         *
         */

    }

    @Override
    @EventListener
    public void onSpanCreatedEventHandler(SpanCreatedEvent aEvent) throws IOException
    {
        AnnotationFS span = aEvent.getAnnotation();

        CreateSpanResponse response = new CreateSpanResponse(
                VID.parse(String.valueOf(aEvent.getAnnotation().getAddress())),
                aEvent.getAnnotation().getCoveredText(), aEvent.getAnnotation().getBegin(),
                aEvent.getAnnotation().getEnd(), aEvent.getAnnotation().getType().getShortName(),
                getColorForAnnotation(span, aEvent.getProject()));
        annotationProcessAPI.sendCreateAnnotationResponse(response,
                String.valueOf(aEvent.getProject().getId()),
                String.valueOf(aEvent.getDocument().getId()),
                String.valueOf(aEvent.getAnnotation().getBegin()));
    }

    @Override
    @EventListener
    public void onSpanDeletedEventHandler(SpanDeletedEvent aEvent) throws IOException
    {
        DeleteSpanResponse response = new DeleteSpanResponse(
                VID.parse(String.valueOf(aEvent.getAnnotation().getAddress())));
        annotationProcessAPI.sendDeleteAnnotationResponse(response,
                String.valueOf(aEvent.getProject().getId()),
                String.valueOf(aEvent.getDocument().getId()),
                String.valueOf(aEvent.getAnnotation().getBegin()));
    }

    @Override
    @EventListener
    public void onRelationCreatedEventHandler(RelationCreatedEvent aEvent) throws IOException
    {

        // TODO get flavor and type feature
        // aEvent.getAnnotation().getFeatureValueAsString("de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:flavor")
        CreateRelationResponse response = new CreateRelationResponse(
                VID.parse(String.valueOf(aEvent.getAnnotation()._id())), aEvent.getUser(),
                aEvent.getUser(), aEvent.getProject().getId(), aEvent.getDocument().getId(),
                VID.parse(String.valueOf(aEvent.getSourceAnnotation()._id())),
                VID.parse(String.valueOf(aEvent.getTargetAnnotation()._id())),
                aEvent.getSourceAnnotation().getCoveredText(),
                aEvent.getTargetAnnotation().getCoveredText(), null, null);

        annotationProcessAPI.sendCreateRelationResponse(response,
                String.valueOf(aEvent.getProject().getId()),
                String.valueOf(aEvent.getDocument().getId()),
                String.valueOf(aEvent.getAnnotation().getBegin()));
    }

    @Override
    @EventListener
    public void onRelationDeletedEventHandler(RelationDeletedEvent aEvent) throws IOException
    {
        System.out.println(aEvent.getRequestTarget().toString());
        System.out.println(aEvent.getSource());
        DeleteRelationResponse response = new DeleteRelationResponse(
                VID.parse(String.valueOf(aEvent.getAnnotation().getAddress())));

        annotationProcessAPI.sendDeleteRelationResponse(response,
                String.valueOf(aEvent.getProject().getId()),
                String.valueOf(aEvent.getDocument().getId()),
                String.valueOf(aEvent.getAnnotation().getBegin()));
    }

    @Override
    public void createErrorMessage(String aMessage, String aUser) throws IOException
    {
        annotationProcessAPI.sendErrorMessage(new ErrorMessage(aMessage), aUser);
    }

    /**
     * --------------------------------------------- ---------- Private support methods ----------
     * ---------------------------------------------
     **/

    public CAS getCasForDocument(String aUser, long aProject, long aDocument)
    {
        try (CasStorageSession session = CasStorageSession.open()) {
            MDC.put(Logging.KEY_REPOSITORY_PATH, repositoryProperties.getPath().toString());
            SourceDocument sourceDocument = documentService.getSourceDocument(aProject, aDocument);
            CAS cas = documentService.readAnnotationCas(sourceDocument, aUser);
            annotationService.upgradeCas(cas, projectService.getProject(aProject));
            return cas;
        }
        catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public List<String> getViewportText(CAS aCas, int[][] aViewport)
    {
        List<String> viewport = new ArrayList<>();
        String text = aCas.getDocumentText();

        for (int i = 0; i < aViewport.length; i++) {
            String sentence = text.substring(aViewport[i][0], aViewport[i][1]);
            viewport.add(sentence);
        }
        return viewport;
    }

    public List<Span> getSpans(CAS aCas, long aProject)
    {
        List<Span> annotations = new ArrayList<>();

        for (FeatureStructure fs : selectAllFS(aCas)) {
            if (fs.getType().getShortName().equals("Token")
                    || fs.getType().getShortName().equals("DocumentMetaData")
                    || fs.getType().getShortName().equals("Sentence")
                    || fs.getType().getShortName().equals("CoreferenceChain")
                    || fs.getType().getShortName().equals("CASMetadata")) {
                continue;
            }
            AnnotationFS annotation = WebAnnoCasUtil.selectAnnotationByAddr(aCas,
                    WebAnnoCasUtil.getAddr(fs));

            Span span = new Span(annotation._id(), annotation.getBegin(), annotation.getEnd(),
                    annotation.getType().getShortName(),
                    getColorForAnnotation(annotation, projectService.getProject(aProject)),
                    annotation.getCoveredText(), getFeatureForSpan(annotation));
            span.setColor(getColorForAnnotation(annotation, projectService.getProject(aProject)));
            annotations.add(span);
        }

        return annotations;
    }

    public String[] getFeatureForSpan(AnnotationFS aSpan)
    {
        List<String> feature = new ArrayList<>();

        for (Feature f : aSpan.getType().getFeatures()) {
            if (f.getRange().getName().equals("uima.cas.String")) {
                if (aSpan.getStringValue(f) != null) {
                    feature.add(aSpan.getStringValue(f));
                }
            }
        }
        return feature.toArray(new String[0]);
    }

    public List<Span> filterSpans(List<Span> aAnnotations, int[][] aViewport)
    {
        List<Span> filteredAnnotations = new ArrayList<>();
        for (Span annotation : aAnnotations) {
            for (int i = 0; i < aViewport.length; i++) {
                for (int j = aViewport[i][0]; j <= aViewport[i][1]; j++) {
                    if (annotation.getBegin() == j) {
                        filteredAnnotations.add(annotation);
                        break;
                    }
                }
            }
        }
        return filteredAnnotations;
    }

    public String getColorForAnnotation(AnnotationFS aAnnotation, Project aProject)
    {
        // TODO proper coloring strategy (without VTypes)
        try {
            TypeAdapter adapter = annotationService.getAdapter(annotationService.findLayer(
                    projectService.getProject(aProject.getId()), aAnnotation.getType().getName()));

            return PALETTE_NORMAL_FILTERED[adapter.getLayer().getId().intValue()
                    % PALETTE_NORMAL_FILTERED.length];
        }
        catch (Exception e) {
            return PALETTE_NORMAL_FILTERED[0];
        }
    }

    public List<Relation> getRelations(CAS aCas, long aProject, int[][] aViewport)
    {
        List<Relation> relations = new ArrayList<>();

        int min = aViewport[0][0];
        int max = aViewport[0][1];

        for (int i = 0; i < aViewport.length; i++) {
            if (aViewport[i][0] < min) {
                min = aViewport[i][0];
            }
            if (aViewport[i][1] > max) {
                max = aViewport[i][1];
            }
        }
        for (FeatureStructure fs : selectAllFS(aCas)) {
            if (fs.getType().getShortName().equals("Dependency")) {
                AnnotationFS annotation = WebAnnoCasUtil.selectAnnotationByAddr(aCas,
                        WebAnnoCasUtil.getAddr(fs));

                String flavor = null;
                String dependencyType = null;
                FeatureStructure governor = null;
                FeatureStructure dependent = null;
                VID governorID = null;
                VID dependentID = null;
                String governorCoveredText = null;
                String dependentCoveredText = null;
                String color = getColorForAnnotation(annotation,
                        projectService.getProject(aProject));

                for (Feature f : annotation.getType().getFeatures()) {

                    if (f.getName().equals(
                            "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:Governor")) {
                        governor = fs.getFeatureValue(f);
                    }
                    if (f.getName().equals(
                            "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:Dependent")) {
                        dependent = fs.getFeatureValue(f);
                    }
                    if (f.getName().equals(
                            "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:DependencyType")) {
                        dependencyType = fs.getStringValue(f);
                    }
                    if (f.getName().equals(
                            "de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency:flavor")) {
                        flavor = fs.getStringValue(f);
                    }
                }

                List<Feature> features = governor.getType().getFeatures();
                for (int i = 3; i < 8; i++) {
                    if (governor.getFeatureValue(features.get(i)) != null) {
                        AnnotationFS govAnnotation = WebAnnoCasUtil.selectAnnotationByAddr(aCas,
                                WebAnnoCasUtil.getAddr(governor));
                        governorID = VID.parse(String.valueOf(govAnnotation._id()));
                        governorCoveredText = govAnnotation.getCoveredText();
                        break;
                    }
                }
                features.clear();
                features = dependent.getType().getFeatures();
                for (int i = 3; i < 8; i++) {
                    if (dependent.getFeatureValue(features.get(i)) != null) {
                        AnnotationFS depAnnotation = WebAnnoCasUtil.selectAnnotationByAddr(aCas,
                                WebAnnoCasUtil.getAddr(dependent));
                        dependentID = VID.parse(String.valueOf(depAnnotation._id()));
                        dependentCoveredText = depAnnotation.getCoveredText();
                        break;
                    }
                }

                relations.add(new Relation(VID.parse(String.valueOf(annotation._id())), governorID,
                        dependentID, color, dependencyType, flavor, governorCoveredText,
                        dependentCoveredText));
            }
        }
        return relations;
    }

    public void getRecommendations(CAS aCas, long aProject)
    {

    }
}
