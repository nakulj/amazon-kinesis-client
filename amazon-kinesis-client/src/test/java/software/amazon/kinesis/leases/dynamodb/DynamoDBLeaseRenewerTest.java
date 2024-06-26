/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package software.amazon.kinesis.leases.dynamodb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import software.amazon.kinesis.common.HashKeyRangeForLease;
import software.amazon.kinesis.leases.Lease;
import software.amazon.kinesis.leases.LeaseRefresher;
import software.amazon.kinesis.leases.exceptions.DependencyException;
import software.amazon.kinesis.leases.exceptions.InvalidStateException;
import software.amazon.kinesis.leases.exceptions.ProvisionedThroughputException;
import software.amazon.kinesis.metrics.NullMetricsFactory;
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class DynamoDBLeaseRenewerTest {
    private final String workerIdentifier = "WorkerId";
    private final long leaseDurationMillis = 10000;
    private DynamoDBLeaseRenewer renewer;
    private List<Lease> leasesToRenew;

    @Mock
    private LeaseRefresher leaseRefresher;

    private static Lease newLease(String leaseKey) {
        return new Lease(
                leaseKey,
                "LeaseOwner",
                0L,
                UUID.randomUUID(),
                System.nanoTime(),
                null,
                null,
                1L,
                new HashSet<>(),
                new HashSet<>(),
                null,
                HashKeyRangeForLease.deserialize("1", "2"));
    }

    @Before
    public void before() {
        leasesToRenew = null;
        renewer = new DynamoDBLeaseRenewer(
                leaseRefresher,
                workerIdentifier,
                leaseDurationMillis,
                Executors.newCachedThreadPool(),
                new NullMetricsFactory());
    }

    @After
    public void after() throws DependencyException, InvalidStateException, ProvisionedThroughputException {
        if (leasesToRenew == null) {
            return;
        }
        for (Lease lease : leasesToRenew) {
            verify(leaseRefresher, times(1)).renewLease(eq(lease));
        }
    }

    @Test
    public void testLeaseRenewerHoldsGoodLeases()
            throws DependencyException, InvalidStateException, ProvisionedThroughputException {
        /*
         * Prepare leases to be renewed
         * 2 Good
         */
        Lease lease1 = newLease("1");
        Lease lease2 = newLease("2");
        leasesToRenew = Arrays.asList(lease1, lease2);
        renewer.addLeasesToRenew(leasesToRenew);

        doReturn(true).when(leaseRefresher).renewLease(lease1);
        doReturn(true).when(leaseRefresher).renewLease(lease2);

        renewer.renewLeases();

        assertEquals(2, renewer.getCurrentlyHeldLeases().size());
    }

    @Test
    public void testLeaseRenewerDoesNotRenewExpiredLease()
            throws DependencyException, InvalidStateException, ProvisionedThroughputException {
        String leaseKey = "expiredLease";
        long initialCounterIncrementNanos = 5L; // "expired" time.
        Lease lease1 = newLease(leaseKey);
        lease1.lastCounterIncrementNanos(initialCounterIncrementNanos);

        leasesToRenew = new ArrayList<>();
        leasesToRenew.add(lease1);
        doReturn(true).when(leaseRefresher).renewLease(lease1);
        renewer.addLeasesToRenew(leasesToRenew);

        assertTrue(lease1.isExpired(1, System.nanoTime()));
        assertNull(renewer.getCurrentlyHeldLease(leaseKey));
        renewer.renewLeases();
        // Don't renew lease(s) with same key if getCurrentlyHeldLease returned null previously
        assertNull(renewer.getCurrentlyHeldLease(leaseKey));
        assertFalse(renewer.getCurrentlyHeldLeases().containsKey(leaseKey));

        // Clear the list to avoid triggering expectation mismatch in after().
        leasesToRenew.clear();
    }

    @Test
    public void testLeaseRenewerDoesNotUpdateInMemoryLeaseIfDDBFailsUpdate()
            throws DependencyException, InvalidStateException, ProvisionedThroughputException {
        String leaseKey = "leaseToUpdate";
        Lease lease = newLease(leaseKey);
        lease.checkpoint(ExtendedSequenceNumber.LATEST);
        leasesToRenew = new ArrayList<>();
        leasesToRenew.add(lease);
        renewer.addLeasesToRenew(leasesToRenew);

        doReturn(true).when(leaseRefresher).renewLease(lease);
        renewer.renewLeases();

        Lease updatedLease = newLease(leaseKey);
        updatedLease.checkpoint(ExtendedSequenceNumber.TRIM_HORIZON);

        doThrow(new DependencyException(new RuntimeException()))
                .when(leaseRefresher)
                .updateLease(updatedLease);

        try {
            UUID concurrencyToken = renewer.getCurrentlyHeldLease(leaseKey).concurrencyToken();
            renewer.updateLease(updatedLease, concurrencyToken, "test", "dummyShardId");
            fail();
        } catch (DependencyException e) {
            // expected
        }
        assertEquals(0L, (long) lease.leaseCounter()); // leaseCounter should not be incremented due to DDB failure
        assertEquals(ExtendedSequenceNumber.LATEST, lease.checkpoint());
    }
}
