/*
 * Copyright (c) 2015 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.ovsdb.hwvtepsouthbound;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.ReadOnlyTransaction;
import org.opendaylight.controller.md.sal.binding.api.ReadWriteTransaction;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.hwvtep.rev150901.HwvtepGlobalAugmentation;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.hwvtep.rev150901.HwvtepGlobalRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.hwvtep.rev150901.HwvtepLogicalSwitchAttributes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.hwvtep.rev150901.HwvtepPhysicalSwitchAttributes;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.data.impl.codec.DeserializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.CheckedFuture;

public class HwvtepSouthboundUtil {

    private static final Logger LOG = LoggerFactory.getLogger(HwvtepSouthboundUtil.class);

    private static InstanceIdentifierCodec instanceIdentifierCodec;

    private HwvtepSouthboundUtil() {
        // Prevent instantiating a utility class
    }

    public static void setInstanceIdentifierCodec(InstanceIdentifierCodec iidc) {
        instanceIdentifierCodec = iidc;
    }

    public static InstanceIdentifierCodec getInstanceIdentifierCodec() {
        return instanceIdentifierCodec;
    }

    public static String serializeInstanceIdentifier(InstanceIdentifier<?> iid) {
        return instanceIdentifierCodec.serialize(iid);
    }

    public static InstanceIdentifier<?> deserializeInstanceIdentifier(String iidString) {
        InstanceIdentifier<?> result = null;
        try {
            result = instanceIdentifierCodec.bindingDeserializer(iidString);
        } catch (DeserializationException e) {
            LOG.warn("Unable to deserialize iidString", e);
        }
        return result;
    }

    public static <D extends org.opendaylight.yangtools.yang.binding.DataObject> Optional<D> readNode(
                    ReadWriteTransaction transaction, final InstanceIdentifier<D> connectionIid) {
        Optional<D> node = Optional.absent();
        try {
            node = transaction.read(LogicalDatastoreType.OPERATIONAL, connectionIid).checkedGet();
        } catch (final ReadFailedException e) {
            LOG.warn("Read Operational/DS for Node failed! {}", connectionIid, e);
        }
        return node;
    }

    public static <D extends org.opendaylight.yangtools.yang.binding.DataObject> boolean deleteNode(
                    ReadWriteTransaction transaction, final InstanceIdentifier<D> connectionIid) {
        boolean result = false;
        transaction.delete(LogicalDatastoreType.OPERATIONAL, connectionIid);
        CheckedFuture<Void, TransactionCommitFailedException> future = transaction.submit();
        try {
            future.checkedGet();
            result = true;
        } catch (TransactionCommitFailedException e) {
            LOG.warn("Failed to delete {} ", connectionIid, e);
        }
        return result;
    }

    public static Optional<HwvtepGlobalAugmentation> getManagingNode(DataBroker db,
                    HwvtepPhysicalSwitchAttributes pNode) {
        Preconditions.checkNotNull(pNode);
        Optional<HwvtepGlobalAugmentation> result = null;
        HwvtepGlobalRef ref = pNode.getManagedBy();
        if (ref != null && ref.getValue() != null) {
            result = getManagingNode(db, ref);
        } else {
            LOG.warn("Cannot find client for PhysicalSwitch without a specified ManagedBy {}", pNode);
            return Optional.absent();
        }
        if (!result.isPresent()) {
            LOG.warn("Failed to find managing node for PhysicalSwitch {}", pNode);
        }
        return result;
    }

    public static Optional<HwvtepGlobalAugmentation> getManagingNode(DataBroker db,
                    HwvtepLogicalSwitchAttributes lNode) {
        Preconditions.checkNotNull(lNode);
        Optional<HwvtepGlobalAugmentation> result = null;

        HwvtepGlobalRef ref = lNode.getLogicalSwitchManagedBy();
        if (ref != null && ref.getValue() != null) {
            result = getManagingNode(db, ref);
        } else {
            LOG.warn("Cannot find client for LogicalSwitch without a specified ManagedBy {}", lNode);
            return Optional.absent();
        }
        if(!result.isPresent()) {
            LOG.warn("Failed to find managing node for PhysicalSwitch {}",lNode);
        }
        return result;
    }

    private static Optional<HwvtepGlobalAugmentation> getManagingNode(DataBroker db, HwvtepGlobalRef ref) {
        try {
            ReadOnlyTransaction transaction = db.newReadOnlyTransaction();
            @SuppressWarnings("unchecked")
            // Note: erasure makes this safe in combination with the typecheck
            // below
            InstanceIdentifier<Node> path = (InstanceIdentifier<Node>) ref.getValue();

            CheckedFuture<Optional<Node>, ReadFailedException> nf =
                            transaction.read(LogicalDatastoreType.OPERATIONAL, path);
            transaction.close();
            Optional<Node> optional = nf.get();
            if (optional != null && optional.isPresent()) {
                HwvtepGlobalAugmentation hwvtepNode = null;
                Node node = optional.get();
                if (node instanceof HwvtepGlobalAugmentation) {
                    hwvtepNode = (HwvtepGlobalAugmentation) node;
                } else if (node != null) {
                    hwvtepNode = node.getAugmentation(HwvtepGlobalAugmentation.class);
                }
                if (hwvtepNode != null) {
                    return Optional.of(hwvtepNode);
                } else {
                    LOG.warn("Hwvtep switch claims to be managed by {} but " + "that HwvtepNode does not exist",
                                    ref.getValue());
                    return Optional.absent();
                }
            } else {
                LOG.warn("Mysteriously got back a thing which is *not* a topology Node: {}", optional);
                return Optional.absent();
            }
        } catch (Exception e) {
            LOG.warn("Failed to get HwvtepNode {}", ref, e);
            return Optional.absent();
        }
    }

}