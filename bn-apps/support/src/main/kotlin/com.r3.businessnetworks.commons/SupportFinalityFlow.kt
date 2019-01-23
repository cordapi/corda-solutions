package com.r3.businessnetworks.commons

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker

/**
 * Executes correct version of FinalityFlow depending on the current platform version.
 * The flow aims to make it easier to build backwards compatible CorDapps across different versions of Corda.
 *
 * @param signedTransaction transaction to be finalised
 * @param progressTracker progress tracker to use. Defaults to FinalityFlow.tracker()
 * @param sessionsCreator lazy initializer for the session with interested participants
 */
class SupportFinalityFlow(private val signedTransaction : SignedTransaction,
                          override val progressTracker : ProgressTracker,
                          private val sessionsCreator : () -> List<FlowSession>) : FlowLogic<SignedTransaction>() {

    constructor(signedTransaction : SignedTransaction,
                sessionsCreator : () -> List<FlowSession>) : this(signedTransaction, FinalityFlow.tracker(), sessionsCreator)

    @Suspendable
    override fun call() : SignedTransaction {
        return if (serviceHub.myInfo.platformVersion < 4) {
            subFlow(FinalityFlow(signedTransaction, progressTracker))
        } else {
            val existingSessions = sessionsCreator()
            val existingSessionsParties = existingSessions.map { it.counterparty as AbstractParty }
            val participants = signedTransaction.tx.outputStates.flatMap { it.participants } - ourIdentity
            val missingSessions = participants - existingSessionsParties
            val sessions = missingSessions.map { initiateFlow(it as Party) }
            subFlow(FinalityFlow(signedTransaction, sessions + existingSessions, progressTracker))
        }
    }
}

class SupportReceiveFinalityFlow(private val otherSideSession: FlowSession,
                                   private val expectedTxId: SecureHash? = null,
                                   private val statesToRecord: StatesToRecord = StatesToRecord.ONLY_RELEVANT) : FlowLogic<SignedTransaction?>(){
    @Suspendable
    override fun call() : SignedTransaction? {
        val otherSideNodeInfo = serviceHub.networkMapCache.getNodeByLegalIdentity(otherSideSession.counterparty)
                ?: throw FlowException("Can't find node info for ${otherSideSession.counterparty}")
        return if (otherSideNodeInfo.platformVersion < 4) {
            // do nothing here
            null
        } else {
            subFlow(ReceiveFinalityFlow(otherSideSession, expectedTxId, statesToRecord))
        }
    }
}