package com.waypoint.dispatch.receipt.contract;

import com.waypoint.dispatch.receipt.contract.ReceiptViews.PendingReceiptView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The only way another module reads receipts. */
public interface ReceiptQuery {

  Optional<ReceiptView> receiptFor(UUID orderId);

  List<PendingReceiptView> pendingConfirmations(String outletId);
}
