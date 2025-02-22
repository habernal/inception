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
package de.tudarmstadt.ukp.inception.ui.core.docanno.sidebar;

import org.apache.wicket.model.IModel;

import de.agilecoders.wicket.core.markup.html.bootstrap.image.IconType;
import de.agilecoders.wicket.extensions.markup.html.bootstrap.icon.FontAwesome5IconType;
import de.tudarmstadt.ukp.clarin.webanno.api.CasProvider;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.AnnotationPage;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.sidebar.AnnotationSidebarFactory_ImplBase;
import de.tudarmstadt.ukp.clarin.webanno.ui.annotation.sidebar.AnnotationSidebar_ImplBase;
import de.tudarmstadt.ukp.inception.editor.action.AnnotationActionHandler;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotatorState;
import de.tudarmstadt.ukp.inception.schema.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.ui.core.docanno.config.DocumentMetadataLayerSupportAutoConfiguration;
import de.tudarmstadt.ukp.inception.ui.core.docanno.layer.DocumentMetadataLayerSupport;

/**
 * Support for document-level annotations through a sidebar.
 * <p>
 * This class is exposed as a Spring Component via
 * {@link DocumentMetadataLayerSupportAutoConfiguration#documentMetadataSidebarFactory}.
 * </p>
 */
public class DocumentMetadataSidebarFactory
    extends AnnotationSidebarFactory_ImplBase
{
    private final AnnotationSchemaService schemaService;

    public DocumentMetadataSidebarFactory(AnnotationSchemaService aSchemaService)
    {
        schemaService = aSchemaService;
    }

    @Override
    public String getDisplayName()
    {
        return "Document Metadata";
    }

    @Override
    public String getDescription()
    {
        return "Document-level annotations.";
    }

    @Override
    public IconType getIcon()
    {
        return FontAwesome5IconType.tags_s;
    }

    @Override
    public boolean available(Project aProject)
    {
        return schemaService.existsEnabledLayerOfType(aProject, DocumentMetadataLayerSupport.TYPE);
    }

    @Override
    public boolean applies(AnnotatorState aState)
    {
        return available(aState.getProject());
    }

    @Override
    public AnnotationSidebar_ImplBase create(String aId, IModel<AnnotatorState> aModel,
            AnnotationActionHandler aActionHandler, CasProvider aCasProvider,
            AnnotationPage aAnnotationPage)
    {
        return new DocumentMetadataSidebar(aId, aModel, aActionHandler, aCasProvider,
                aAnnotationPage);
    }
}
