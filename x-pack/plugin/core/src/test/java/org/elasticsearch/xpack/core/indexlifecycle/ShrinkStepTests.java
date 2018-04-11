/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.indexlifecycle;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.rollover.Condition;
import org.elasticsearch.action.admin.indices.rollover.MaxAgeCondition;
import org.elasticsearch.action.admin.indices.rollover.MaxDocsCondition;
import org.elasticsearch.action.admin.indices.rollover.MaxSizeCondition;
import org.elasticsearch.action.admin.indices.rollover.RolloverIndexTestHelper;
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest;
import org.elasticsearch.action.admin.indices.rollover.RolloverResponse;
import org.elasticsearch.action.admin.indices.shrink.ResizeAction;
import org.elasticsearch.action.admin.indices.shrink.ResizeRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.EqualsHashCodeTestUtils;
import org.elasticsearch.xpack.core.indexlifecycle.AsyncActionStep.Listener;
import org.elasticsearch.xpack.core.indexlifecycle.Step.StepKey;
import org.junit.Before;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.equalTo;

public class ShrinkStepTests extends ESTestCase {

    private Client client;

    @Before
    public void setup() {
        client = Mockito.mock(Client.class);
    }

    public ShrinkStep createRandomInstance() {
        StepKey stepKey = new StepKey(randomAlphaOfLength(10), randomAlphaOfLength(10), randomAlphaOfLength(10));
        StepKey nextStepKey = new StepKey(randomAlphaOfLength(10), randomAlphaOfLength(10), randomAlphaOfLength(10));
        String shrunkenIndexName = randomAlphaOfLengthBetween(1, 20);
        return new ShrinkStep(stepKey, nextStepKey, client, shrunkenIndexName);
    }

    public ShrinkStep mutateInstance(ShrinkStep instance) {
        StepKey key = instance.getKey();
        StepKey nextKey = instance.getNextStepKey();
        String shrunkenIndexName = instance.getShrunkenIndexName();

        switch (between(0, 2)) {
        case 0:
            key = new StepKey(key.getPhase(), key.getAction(), key.getName() + randomAlphaOfLength(5));
            break;
        case 1:
            nextKey = new StepKey(key.getPhase(), key.getAction(), key.getName() + randomAlphaOfLength(5));
            break;
        case 2:
            shrunkenIndexName = shrunkenIndexName + randomAlphaOfLengthBetween(1, 5);
            break;
        default:
            throw new AssertionError("Illegal randomisation branch");
        }

        return new ShrinkStep(key, nextKey, instance.getClient(), shrunkenIndexName);
    }

    public void testHashcodeAndEquals() {
        EqualsHashCodeTestUtils
                .checkEqualsAndHashCode(createRandomInstance(),
                        instance -> new ShrinkStep(instance.getKey(), instance.getNextStepKey(), instance.getClient(),
                                instance.getShrunkenIndexName()),
                        this::mutateInstance);
    }

    public void testPerformAction() throws Exception {
        long creationDate = randomNonNegativeLong();
        IndexMetaData sourceIndexMetaData = IndexMetaData.builder(randomAlphaOfLength(10))
            .settings(settings(Version.CURRENT).put(LifecycleSettings.LIFECYCLE_INDEX_CREATION_DATE, creationDate))
            .numberOfShards(randomIntBetween(1, 5)).numberOfReplicas(randomIntBetween(0, 5))
            .putAlias(AliasMetaData.builder("my_alias"))
            .build();

        ShrinkStep step = createRandomInstance();

        AdminClient adminClient = Mockito.mock(AdminClient.class);
        IndicesAdminClient indicesClient = Mockito.mock(IndicesAdminClient.class);

        Mockito.when(client.admin()).thenReturn(adminClient);
        Mockito.when(adminClient.indices()).thenReturn(indicesClient);
        Mockito.doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ResizeRequest request = (ResizeRequest) invocation.getArguments()[0];
                @SuppressWarnings("unchecked")
                ActionListener<ResizeResponse> listener = (ActionListener<ResizeResponse>) invocation.getArguments()[1];
                assertThat(request.getSourceIndex(), equalTo(sourceIndexMetaData.getIndex().getName()));
                assertThat(request.getTargetIndexRequest().aliases(), equalTo(Collections.singleton(new Alias("my_alias"))));
                assertThat(request.getTargetIndexRequest().settings(), equalTo(Settings.builder()
                    .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, sourceIndexMetaData.getNumberOfShards())
                    .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, sourceIndexMetaData.getNumberOfReplicas())
                    .put(LifecycleSettings.LIFECYCLE_INDEX_CREATION_DATE, creationDate).build()));
                assertThat(request.getTargetIndexRequest().index(), equalTo(step.getShrunkenIndexName()));
                ResizeResponse resizeResponse = ResizeAction.INSTANCE.newResponse();
                resizeResponse.readFrom(StreamInput.wrap(new byte[] { 1, 1, 1, 1, 1 }));
                listener.onResponse(resizeResponse);
                return null;
            }

        }).when(indicesClient).resizeIndex(Mockito.any(), Mockito.any());

        SetOnce<Boolean> actionCompleted = new SetOnce<>();
        step.performAction(sourceIndexMetaData, new Listener() {

            @Override
            public void onResponse(boolean complete) {
                actionCompleted.set(complete);
            }

            @Override
            public void onFailure(Exception e) {
                throw new AssertionError("Unexpected method call", e);
            }
        });

        assertEquals(true, actionCompleted.get());

        Mockito.verify(client, Mockito.only()).admin();
        Mockito.verify(adminClient, Mockito.only()).indices();
        Mockito.verify(indicesClient, Mockito.only()).resizeIndex(Mockito.any(), Mockito.any());
    }

    public void testPerformActionNotComplete() throws Exception {
        IndexMetaData indexMetaData = IndexMetaData.builder(randomAlphaOfLength(10)).settings(settings(Version.CURRENT))
            .numberOfShards(randomIntBetween(1, 5)).numberOfReplicas(randomIntBetween(0, 5)).build();
        ShrinkStep step = createRandomInstance();

        AdminClient adminClient = Mockito.mock(AdminClient.class);
        IndicesAdminClient indicesClient = Mockito.mock(IndicesAdminClient.class);

        Mockito.when(client.admin()).thenReturn(adminClient);
        Mockito.when(adminClient.indices()).thenReturn(indicesClient);
        Mockito.doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                @SuppressWarnings("unchecked")
                ActionListener<ResizeResponse> listener = (ActionListener<ResizeResponse>) invocation.getArguments()[1];
                ResizeResponse resizeResponse = ResizeAction.INSTANCE.newResponse();
                resizeResponse.readFrom(StreamInput.wrap(new byte[] { 0, 0, 0, 0, 0 }));
                listener.onResponse(resizeResponse);
                return null;
            }

        }).when(indicesClient).resizeIndex(Mockito.any(), Mockito.any());

        SetOnce<Boolean> actionCompleted = new SetOnce<>();
        step.performAction(indexMetaData, new Listener() {

            @Override
            public void onResponse(boolean complete) {
                actionCompleted.set(complete);
            }

            @Override
            public void onFailure(Exception e) {
                throw new AssertionError("Unexpected method call", e);
            }
        });

        assertEquals(false, actionCompleted.get());

        Mockito.verify(client, Mockito.only()).admin();
        Mockito.verify(adminClient, Mockito.only()).indices();
        Mockito.verify(indicesClient, Mockito.only()).resizeIndex(Mockito.any(), Mockito.any());
    }

    public void testPerformActionFailure() throws Exception {
        IndexMetaData indexMetaData = IndexMetaData.builder(randomAlphaOfLength(10)).settings(settings(Version.CURRENT))
            .numberOfShards(randomIntBetween(1, 5)).numberOfReplicas(randomIntBetween(0, 5)).build();
        Exception exception = new RuntimeException();
        ShrinkStep step = createRandomInstance();

        AdminClient adminClient = Mockito.mock(AdminClient.class);
        IndicesAdminClient indicesClient = Mockito.mock(IndicesAdminClient.class);

        Mockito.when(client.admin()).thenReturn(adminClient);
        Mockito.when(adminClient.indices()).thenReturn(indicesClient);
        Mockito.doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                @SuppressWarnings("unchecked")
                ActionListener<RolloverResponse> listener = (ActionListener<RolloverResponse>) invocation.getArguments()[1];
                listener.onFailure(exception);
                return null;
            }

        }).when(indicesClient).resizeIndex(Mockito.any(), Mockito.any());

        SetOnce<Boolean> exceptionThrown = new SetOnce<>();
        step.performAction(indexMetaData, new Listener() {

            @Override
            public void onResponse(boolean complete) {
                throw new AssertionError("Unexpected method call");
            }

            @Override
            public void onFailure(Exception e) {
                assertSame(exception, e);
                exceptionThrown.set(true);
            }
        });

        assertEquals(true, exceptionThrown.get());

        Mockito.verify(client, Mockito.only()).admin();
        Mockito.verify(adminClient, Mockito.only()).indices();
        Mockito.verify(indicesClient, Mockito.only()).resizeIndex(Mockito.any(), Mockito.any());
    }

}
