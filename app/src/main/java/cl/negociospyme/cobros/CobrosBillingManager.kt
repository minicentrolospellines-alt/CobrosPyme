package cl.negociospyme.cobros

import android.app.Activity
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

private const val PRO_MONTHLY_PRODUCT_ID = "cobrospyme_pro_mensual"
private const val PRO_ANNUAL_PRODUCT_ID = "cobrospyme_pro_anual"

class CobrosBillingManager(
    private val activity: Activity,
    private val onProStateChanged: (Boolean) -> Unit,
    private val onPricesChanged: (monthly: String?, annual: String?) -> Unit
) : PurchasesUpdatedListener {

    private val productDetails = mutableMapOf<String, ProductDetails>()

    private val billingClient: BillingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (billingClient.isReady) {
            queryProducts()
            restorePurchases(showMessage = false)
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
                    restorePurchases(showMessage = false)
                } else {
                    toast("Google Play Billing no está disponible todavía.")
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection() intentará reconectar en la próxima llamada.
            }
        })
    }

    private fun queryProducts() {
        if (!billingClient.isReady) return

        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_MONTHLY_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_ANNUAL_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, result ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                return@queryProductDetailsAsync
            }

            productDetails.clear()
            result.productDetailsList.forEach { details ->
                productDetails[details.productId] = details
            }

            onPricesChanged(
                formattedPrice(productDetails[PRO_MONTHLY_PRODUCT_ID]),
                formattedPrice(productDetails[PRO_ANNUAL_PRODUCT_ID])
            )
        }
    }

    fun buyMonthly() = launchPurchase(PRO_MONTHLY_PRODUCT_ID)

    fun buyAnnual() = launchPurchase(PRO_ANNUAL_PRODUCT_ID)

    private fun launchPurchase(productId: String) {
        if (!billingClient.isReady) {
            toast("Conectando con Google Play. Intenta nuevamente en unos segundos.")
            start()
            return
        }

        val details = productDetails[productId]
        if (details == null) {
            toast("Este plan aún no está disponible. Revisa su configuración en Play Console.")
            queryProducts()
            return
        }

        val offer = details.subscriptionOfferDetails?.firstOrNull()
        if (offer == null) {
            toast("No hay un plan de suscripción disponible para esta cuenta.")
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            toast("No se pudo abrir Google Play: ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                handlePurchases(purchases.orEmpty(), showSuccessMessage = true)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                restorePurchases(showMessage = true)
            }
            else -> {
                toast("No se pudo completar la compra: ${billingResult.debugMessage}")
            }
        }
    }

    fun restorePurchases(showMessage: Boolean) {
        if (!billingClient.isReady) {
            if (showMessage) toast("Conectando con Google Play…")
            start()
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases, showSuccessMessage = showMessage)
            } else if (showMessage) {
                toast("No se pudieron restaurar las compras.")
            }
        }
    }

    private fun handlePurchases(
        purchases: List<Purchase>,
        showSuccessMessage: Boolean
    ) {
        val proPurchases = purchases.filter { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                purchase.products.any {
                    it == PRO_MONTHLY_PRODUCT_ID || it == PRO_ANNUAL_PRODUCT_ID
                }
        }

        val hasPro = proPurchases.isNotEmpty()
        onProStateChanged(hasPro)

        proPurchases.forEach { purchase ->
            if (!purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient.acknowledgePurchase(params) { result ->
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        toast("La compra se recibió, pero falta confirmarla con Google Play.")
                    }
                }
            }
        }

        if (showSuccessMessage) {
            if (hasPro) {
                toast("CobrosPyme Pro activado ✓")
            } else {
                toast("No se encontró una suscripción Pro activa.")
            }
        }
    }

    private fun formattedPrice(details: ProductDetails?): String? {
        return details
            ?.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.lastOrNull()
            ?.formattedPrice
    }

    fun close() {
        billingClient.endConnection()
    }

    private fun toast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }
}
