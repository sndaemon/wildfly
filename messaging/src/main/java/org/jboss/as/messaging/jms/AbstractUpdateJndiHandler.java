/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.messaging.jms;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.messaging.HornetQActivationService.rollbackOperationIfServerNotActive;
import static org.jboss.as.messaging.MessagingMessages.MESSAGES;

import org.hornetq.jms.server.JMSServerManager;
import org.jboss.as.controller.ControllerMessages;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.operations.validation.StringLengthValidator;
import org.jboss.as.messaging.CommonAttributes;
import org.jboss.as.messaging.MessagingServices;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;

/**
 * Base class for handlers that handle "add-jndi" and "remove-jndi" operations.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public abstract class AbstractUpdateJndiHandler implements OperationStepHandler {

    protected static final String ADD_JNDI = "add-jndi";
    protected static final String REMOVE_JNDI = "remove-jndi";

    protected static final SimpleAttributeDefinition JNDI_BINDING = new SimpleAttributeDefinitionBuilder(CommonAttributes.JNDI_BINDING, ModelType.STRING)
            .setAllowNull(false)
            .setValidator(new StringLengthValidator(1))
            .build();

    /**
     * {@code true} if the handler is for the add operation, {@code false} if it is for the remove operation.
     */
    private final boolean addOperation;

    protected  AbstractUpdateJndiHandler(boolean addOperation) {
        this.addOperation = addOperation;
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
        JNDI_BINDING.validateOperation(operation);
        final String jndiName = JNDI_BINDING.resolveModelAttribute(context, operation).asString();
        final ModelNode entries = context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel().get(CommonAttributes.DESTINATION_ENTRIES.getName());

        if (addOperation) {
            for (ModelNode entry : entries.asList()) {
                if (jndiName.equals(entry.asString())) {
                    throw new OperationFailedException(new ModelNode().set(MESSAGES.jndiNameAlreadyRegistered(jndiName)));
                }
            }
            entries.add(jndiName);
        } else {
            ModelNode updatedEntries = new ModelNode();
            boolean updated = false;
            for (ModelNode entry : entries.asList()) {
                if (jndiName.equals(entry.asString())) {
                    if (entries.asList().size() == 1) {
                        throw new OperationFailedException(new ModelNode().set(MESSAGES.canNotRemoveLastJNDIName(jndiName)));
                    }
                    updated = true;
                } else {
                    updatedEntries.add(entry);
                }
            }
            if (updated) {
                context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel().get(CommonAttributes.DESTINATION_ENTRIES.getName()).set(updatedEntries);
            }
        }


        if (context.isNormalServer()) {
            if (rollbackOperationIfServerNotActive(context, operation)) {
                return;
            }

            context.addStep(new OperationStepHandler() {
                @Override
                public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                    final String resourceName = PathAddress.pathAddress(operation.require(ModelDescriptionConstants.OP_ADDR)).getLastElement().getValue();

                    final ServiceName hqServiceName = MessagingServices.getHornetQServiceName(PathAddress.pathAddress(operation.get(ModelDescriptionConstants.OP_ADDR)));
                    final ServiceName jmsManagerServiceName = JMSServices.getJmsManagerBaseServiceName(hqServiceName);

                    final ServiceController<?> jmsServerService = context.getServiceRegistry(false).getService(jmsManagerServiceName);
                    if (jmsServerService != null) {
                        JMSServerManager jmsServerManager = JMSServerManager.class.cast(jmsServerService.getValue());

                        if (jmsServerManager == null) {
                            PathAddress address = PathAddress.pathAddress(operation.require(OP_ADDR));
                            throw ControllerMessages.MESSAGES.managementResourceNotFound(address);
                        }

                        try {
                            if (addOperation) {
                                addJndiName(jmsServerManager, resourceName, jndiName);
                            } else {
                                removeJndiName(jmsServerManager, resourceName, jndiName);
                            }
                        } catch (Exception e) {
                            context.getFailureDescription().set(e.getLocalizedMessage());
                        }

                    } // else the subsystem isn't started yet

                    if (!context.hasFailureDescription()) {
                        context.getResult();
                    }

                    context.completeStep(new OperationContext.RollbackHandler() {
                        @Override
                        public void handleRollback(OperationContext context, ModelNode operation) {
                            if (jmsServerService != null) {
                                JMSServerManager jmsServerManager = JMSServerManager.class.cast(jmsServerService.getValue());

                                try {
                                    if (addOperation) {
                                        removeJndiName(jmsServerManager, resourceName, jndiName);
                                    } else {
                                        addJndiName(jmsServerManager, resourceName, jndiName);
                                    }
                                } catch (Exception e) {
                                    context.getFailureDescription().set(e.getLocalizedMessage());
                                }
                            }
                        }
                    });
                }
            }, OperationContext.Stage.RUNTIME);
        }
        context.stepCompleted();
    }

    protected abstract void addJndiName(JMSServerManager jmsServerManager, String resourceName, String jndiName) throws Exception;

    protected abstract void removeJndiName(JMSServerManager jmsServerManager, String resourceName, String jndiName) throws Exception;
}
