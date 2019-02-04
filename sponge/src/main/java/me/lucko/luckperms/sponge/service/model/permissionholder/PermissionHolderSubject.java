/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge.service.model.permissionholder;

import com.google.common.collect.ImmutableList;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.Tristate;
import me.lucko.luckperms.api.context.ImmutableContextSet;
import me.lucko.luckperms.common.cacheddata.type.MetaCache;
import me.lucko.luckperms.common.graph.TraversalAlgorithm;
import me.lucko.luckperms.common.inheritance.InheritanceGraph;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.NodeMapType;
import me.lucko.luckperms.common.model.PermissionHolder;
import me.lucko.luckperms.common.node.factory.NodeFactory;
import me.lucko.luckperms.common.node.model.NodeTypes;
import me.lucko.luckperms.common.verbose.event.MetaCheckEvent;
import me.lucko.luckperms.common.verbose.event.PermissionCheckEvent;
import me.lucko.luckperms.sponge.LPSpongePlugin;
import me.lucko.luckperms.sponge.model.SpongeGroup;
import me.lucko.luckperms.sponge.service.LuckPermsService;
import me.lucko.luckperms.sponge.service.ProxyFactory;
import me.lucko.luckperms.sponge.service.model.LPSubject;
import me.lucko.luckperms.sponge.service.model.LPSubjectReference;

import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.service.permission.Subject;

import java.util.Optional;

/**
 * Implements {@link LPSubject} for a {@link PermissionHolder}.
 */
public abstract class PermissionHolderSubject<T extends PermissionHolder> implements LPSubject {
    protected final T parent;
    protected final LPSpongePlugin plugin;

    private final PermissionHolderSubjectData subjectData;
    private final PermissionHolderSubjectData transientSubjectData;

    private Subject spongeSubject = null;

    PermissionHolderSubject(LPSpongePlugin plugin, T parent) {
        this.parent = parent;
        this.plugin = plugin;
        this.subjectData = new PermissionHolderSubjectData(plugin.getService(), NodeMapType.ENDURING, parent, this);
        this.transientSubjectData = new PermissionHolderSubjectData(plugin.getService(), NodeMapType.TRANSIENT, parent, this);
    }

    public void fireUpdateEvent() {
        this.plugin.getUpdateEventHandler().fireUpdateEvent(this.subjectData);
        this.plugin.getUpdateEventHandler().fireUpdateEvent(this.transientSubjectData);
    }

    public T getParent() {
        return this.parent;
    }

    @Override
    public synchronized Subject sponge() {
        if (this.spongeSubject == null) {
            this.spongeSubject = ProxyFactory.toSponge(this);
        }
        return this.spongeSubject;
    }

    @Override
    public LuckPermsService getService() {
        return this.plugin.getService();
    }

    @Override
    public LPSubject getDefaults() {
        return this.plugin.getService().getDefaultSubjects().getTypeDefaults(getParentCollection().getIdentifier());
    }

    @Override
    public PermissionHolderSubjectData getSubjectData() {
        return this.subjectData;
    }

    @Override
    public PermissionHolderSubjectData getTransientSubjectData() {
        return this.transientSubjectData;
    }

    @Override
    public Tristate getPermissionValue(ImmutableContextSet contexts, String permission) {
        Contexts lookupContexts = this.plugin.getContextManager().formContexts(contexts);
        return this.parent.getCachedData().getPermissionData(lookupContexts).getPermissionValue(permission, PermissionCheckEvent.Origin.PLATFORM_LOOKUP_CHECK).result();
    }

    @Override
    public boolean isChildOf(ImmutableContextSet contexts, LPSubjectReference parent) {
        return parent.getCollectionIdentifier().equals(PermissionService.SUBJECTS_GROUP) &&
                getPermissionValue(contexts, NodeFactory.groupNode(parent.getSubjectIdentifier())).asBoolean();
    }

    @Override
    public ImmutableList<LPSubjectReference> getParents(ImmutableContextSet contexts) {
        InheritanceGraph graph = this.plugin.getInheritanceHandler().getGraph(this.plugin.getContextManager().formContexts(contexts));
        Iterable<PermissionHolder> traversal = graph.traverse(TraversalAlgorithm.DEPTH_FIRST_PRE_ORDER, this.parent);

        ImmutableList.Builder<LPSubjectReference> subjects = ImmutableList.builder();
        for (PermissionHolder parent : traversal) {
            if (!(parent instanceof Group)) {
                continue;
            }

            subjects.add(((SpongeGroup) parent).sponge().toReference());
        }
        return subjects.build();
    }

    @Override
    public Optional<String> getOption(ImmutableContextSet contexts, String s) {
        MetaCache data = this.parent.getCachedData().getMetaData(this.plugin.getContextManager().formContexts(contexts));
        if (s.equalsIgnoreCase(NodeTypes.PREFIX_KEY)) {
            String prefix = data.getPrefix(MetaCheckEvent.Origin.PLATFORM_API);
            if (prefix != null) {
                return Optional.of(prefix);
            }
        }

        if (s.equalsIgnoreCase(NodeTypes.SUFFIX_KEY)) {
            String suffix = data.getSuffix(MetaCheckEvent.Origin.PLATFORM_API);
            if (suffix != null) {
                return Optional.of(suffix);
            }
        }

        String val = data.getMeta(MetaCheckEvent.Origin.PLATFORM_API).get(s);
        if (val != null) {
            return Optional.of(val);
        }

        Optional<String> v = getParentCollection().getDefaults().getOption(contexts, s);
        if (v.isPresent()) {
            return v;
        }

        return this.plugin.getService().getRootDefaults().getOption(contexts, s);
    }

    @Override
    public void invalidateCaches() {
        // invalidate for all changes
        this.parent.invalidateCachedData();
    }

}