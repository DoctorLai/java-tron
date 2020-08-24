package org.tron.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Commons;
import org.tron.common.utils.DecodeUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.db.CrossRevokingStore;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.AccountStore;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.code;
import org.tron.protos.contract.CrossChain.VoteCrossContract;

@Slf4j(topic = "actuator")
public class VoteCrossActuator extends AbstractActuator {


  public VoteCrossActuator() {
    super(ContractType.VoteCrossContract, VoteCrossContract.class);
  }

  @Override
  public boolean execute(Object object) throws ContractExeException {
    TransactionResultCapsule ret = (TransactionResultCapsule) object;
    if (Objects.isNull(ret)) {
      throw new RuntimeException(ActuatorConstant.TX_RESULT_NULL);
    }

    long fee = calcFee();
    try {
      VoteCrossContract voteCrossContract = any.unpack(VoteCrossContract.class);
      AccountStore accountStore = chainBaseManager.getAccountStore();
      CrossRevokingStore crossRevokingStore = chainBaseManager.getCrossRevokingStore();
      String chainId = voteCrossContract.getChainId().toString();
      long amount = voteCrossContract.getAmount();
      byte[] address = voteCrossContract.getOwnerAddress().toByteArray();
      long voted = crossRevokingStore.getChainVote(chainId, ByteArray.toHexString(address));
      Commons.adjustBalance(accountStore, address, -amount);
      crossRevokingStore.putChainVote(chainId, ByteArray.toHexString(address), voted + amount);
      crossRevokingStore.updateTotalChainVote(chainId, amount);

      Commons.adjustBalance(accountStore, address, -fee);
      Commons.adjustBalance(accountStore, accountStore.getBlackhole().createDbKey(), fee);
      ret.setStatus(fee, code.SUCESS);
    } catch (InvalidProtocolBufferException | BalanceInsufficientException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }

  @Override
  public boolean validate() throws ContractValidateException {
    if (this.any == null) {
      throw new ContractValidateException(ActuatorConstant.CONTRACT_NOT_EXIST);
    }
    if (chainBaseManager == null) {
      throw new ContractValidateException(ActuatorConstant.STORE_NOT_EXIST);
    }
    AccountStore accountStore = chainBaseManager.getAccountStore();
    CrossRevokingStore crossRevokingStore = chainBaseManager.getCrossRevokingStore();
    if (!this.any.is(VoteCrossContract.class)) {
      throw new ContractValidateException(
          "contract type error, expected type [VoteCrossContract], real type[" + any
              .getClass() + "]");
    }
    final VoteCrossContract contract;
    try {
      contract = this.any.unpack(VoteCrossContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }

    String chainId = contract.getChainId().toString();
    long amount = contract.getAmount();
    byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
    if (!DecodeUtil.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid address");
    }
    AccountCapsule accountCapsule = accountStore.get(ownerAddress);
    if (accountCapsule.getBalance() - amount < 0) {
      throw new ContractValidateException(
              "Validate VoteCrossContract error, balance is not sufficient.");
    }

    String readableOwnerAddress = ByteArray.toHexString(ownerAddress);
    long voteCountBefore = crossRevokingStore.getChainVote(chainId, readableOwnerAddress);

    if (voteCountBefore + amount < 0) {
      throw new ContractValidateException(
          "the amount for revoke is larger than the vote count.");
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return any.unpack(VoteCrossContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 1_000_000L;
  }

}