package net.corda.did.flows
/**
 * Persistent code
 *
 */
import co.paralleluniverse.fibers.Suspendable
import com.natpryce.Result
import com.natpryce.map
import com.natpryce.onFailure
import com.natpryce.valueOrNull
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.did.CordaDid
import net.corda.did.DidEnvelope
import net.corda.did.DidInstruction
import net.corda.did.utils.*
import net.corda.did.contract.DidContract
import net.corda.did.state.DidState
import net.corda.did.state.DidStatus
import net.corda.did.utils.DIDNotFoundException
import java.util.*

@InitiatingFlow
@StartableByRPC
// ??? moritzplatt 2019-06-20 -- consider passing the envelope only (see notes on CreateDidFLow)
// ??? moritzplatt 2019-06-20 -- does that even need a whole envelope? wouldn't an instruction be enough?
class DeleteDidFlow(val instruction: DidInstruction, val did: String) : FlowLogic<SignedTransaction>() {

    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new DidState.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    /**
     * The flow logic is encapsulated within the call() method.
     */
    @Suspendable
    override fun call(): SignedTransaction {

        // ??? moritzplatt 2019-06-20 -- previous comments on UUID vs id apply
        // query the ledger if did exist or not
        var didStates: List<StateAndRef<DidState>> = listOf()
        CordaDid.parseExternalForm(did).map {
            didStates = serviceHub.loadState(UniqueIdentifier(null, it.uuid), DidState::class.java)
        }

        if( didStates.isEmpty() ) {
            throw DIDNotFoundException("DID with id $did does not exist")
        }
        val inputDidState = didStates.singleOrNull()!!

        // Obtain a reference to the notary we want to use.
        // ??? moritzplatt 2019-06-20 -- previous comment on notary selection applies
        val notary = serviceHub.getNotaryFromConfig()

        // Stage 1.
        progressTracker.currentStep = GENERATING_TRANSACTION

        // Generate an unsigned transaction.
        val txCommand = Command(DidContract.Commands.Delete(), listOf(ourIdentity.owningKey))
        val txBuilder = TransactionBuilder(notary)
                .addInputState(inputDidState)
                .addOutputState(inputDidState.state.data.copy(status = DidStatus.DELETED, envelope = DidEnvelope(instruction.source, inputDidState.state.data.envelope.document.source)), DidContract.DID_CONTRACT_ID)
                .addCommand(txCommand)

        // Stage 2.
        progressTracker.currentStep = VERIFYING_TRANSACTION
        // Verify that the transaction is valid.
        txBuilder.verify(serviceHub)

        // Stage 3.
        progressTracker.currentStep = SIGNING_TRANSACTION
        // Sign the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Stage 5.
        progressTracker.currentStep = FINALISING_TRANSACTION

        val otherPartySession = inputDidState.state.data.witnesses.map { initiateFlow(it) }.toSet()
        // Notarise and record the transaction in witness parties' vaults.
        return subFlow(FinalityFlow(signedTx, otherPartySession, FINALISING_TRANSACTION.childProgressTracker()))
    }
}

@InitiatedBy(DeleteDidFlow::class)
class DeleteDidFinalityFlowResponder(private val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ReceiveFinalityFlow(otherPartySession))
    }
}

