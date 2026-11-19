package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Business
import com.example.data.model.CatalogItem
import com.example.data.model.Customer
import com.example.data.model.EffectiveEntitlements
import com.example.data.model.ReceiptRecord
import com.example.data.model.StaffRole
import com.example.data.model.VerificationStatus
import com.example.ui.components.KpiMetricCard
import com.example.ui.components.VerificationStatusChip
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.SovereignNavy
import com.example.util.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CustomersScreen(
    business: Business,
    role: StaffRole,
    customers: List<Customer>,
    catalog: List<CatalogItem>,
    receipts: List<ReceiptRecord>,
    effectiveEntitlements: EffectiveEntitlements? = null,
    onUpsertCustomer: (Customer) -> Unit,
    onSaveCatalogItem: (CatalogItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tabIndex by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var editingCustomer by remember { mutableStateOf<Customer?>(null) }
    var viewingCustomer by remember { mutableStateOf<Customer?>(null) }
    var editingCatalog by remember { mutableStateOf<CatalogItem?>(null) }

    editingCustomer?.let { target ->
        var fullName by remember(target) { mutableStateOf(target.fullName) }
        var phone by remember(target) { mutableStateOf(target.phone) }
        var email by remember(target) { mutableStateOf(target.email) }
        var address by remember(target) { mutableStateOf(target.address) }
        var errMsg by remember(target) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editingCustomer = null },
            title = { Text(if (target.id.isBlank()) "Add Customer" else "Edit Customer") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = fullName, onValueChange = { fullName = it; errMsg = null }, label = { Text("Full Name *") })
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") })
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email Address") })
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") })
                    if (!errMsg.isNullOrBlank()) {
                        Text(errMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (fullName.trim().isBlank()) {
                            errMsg = "Customer full name is required."
                        } else {
                            onUpsertCustomer(
                                target.copy(
                                    fullName = fullName.trim(),
                                    phone = phone.trim(),
                                    email = email.trim(),
                                    address = address.trim()
                                )
                            )
                            editingCustomer = null
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingCustomer = null }) { Text("Cancel") } }
        )
    }

    viewingCustomer?.let { cust ->
        val custReceipts = receipts.filter { it.customerId == cust.id || it.customerName.equals(cust.fullName, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { viewingCustomer = null },
            title = { Text(cust.fullName) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Code: ${cust.customerCode}", fontFamily = FontFamily.Monospace)
                    Text("Phone: ${cust.phone.ifBlank { "—" }}  •  Email: ${cust.email.ifBlank { "—" }}")
                    Text("Verified Total: ₦${"%,.2f".format(cust.totalVerifiedAmount)}", fontWeight = FontWeight.Bold, color = NigerianEmerald)
                    Text("Receipts Issued: ${custReceipts.size}", fontWeight = FontWeight.SemiBold)
                    custReceipts.take(8).forEach { r ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.receiptNumber, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("₦${"%,.2f".format(r.amountPaid)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                if (role.canManageCustomers()) {
                    TextButton(onClick = { viewingCustomer = null; editingCustomer = cust }) { Text("Edit") }
                }
            },
            dismissButton = { TextButton(onClick = { viewingCustomer = null }) { Text("Close") } }
        )
    }

    editingCatalog?.let { item ->
        var name by remember(item) { mutableStateOf(item.name) }
        var priceStr by remember(item) { mutableStateOf(if (item.unitPrice > 0) item.unitPrice.toString() else "") }
        var cat by remember(item) { mutableStateOf(item.category) }
        var desc by remember(item) { mutableStateOf(item.description) }
        var errMsg by remember(item) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editingCatalog = null },
            title = { Text(if (item.id.isBlank()) "Add Product / Service" else "Edit Catalog Item") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = name, onValueChange = { name = it; errMsg = null }, label = { Text("Item Name *") })
                    OutlinedTextField(value = priceStr, onValueChange = { priceStr = it; errMsg = null }, label = { Text("Unit Price (NGN) *") })
                    OutlinedTextField(value = cat, onValueChange = { cat = it }, label = { Text("Category") })
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") })
                    if (!errMsg.isNullOrBlank()) {
                        Text(errMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanedPrice = priceStr.replace(",", "").replace("₦", "").trim()
                        val price = cleanedPrice.toDoubleOrNull()
                        if (name.trim().isBlank()) {
                            errMsg = "Item name is required."
                        } else if (price == null || price < 0.0) {
                            errMsg = "Enter a valid non-negative unit price."
                        } else {
                            onSaveCatalogItem(
                                item.copy(
                                    name = name.trim(),
                                    unitPrice = price,
                                    category = cat.trim(),
                                    description = desc.trim()
                                )
                            )
                            editingCatalog = null
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingCatalog = null }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Customers (${customers.size})") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Catalog (${catalog.size})") })
        }

        if (tabIndex == 0) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search customer name, phone, email...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    val maxCust = effectiveEntitlements?.maxCustomers ?: -1
                    val atCustomerCap = maxCust >= 0 && customers.size >= maxCust
                    if (role.canManageCustomers()) {
                        Button(
                            onClick = { editingCustomer = Customer() },
                            enabled = !atCustomerCap,
                            modifier = Modifier.height(54.dp).testTag("add_customer_button")
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Add Customer")
                        }
                    }
                }
                if ((effectiveEntitlements?.maxCustomers ?: -1) >= 0) {
                    Text(
                        "Customer Directory: ${customers.size} / ${effectiveEntitlements?.maxCustomers} allowed on ${effectiveEntitlements?.planName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (customers.size >= (effectiveEntitlements?.maxCustomers ?: Int.MAX_VALUE)) AlertCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (role.canExportReports() && customers.isNotEmpty() && effectiveEntitlements?.reportsExportEnabled != false) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ReportExporter.exportCsvAndShare(
                                        context = context,
                                        filePrefix = "${business.receiptPrefix}_Customers",
                                        headers = listOf("Code", "Full Name", "Phone", "Email", "Receipts", "Verified Spend (NGN)"),
                                        rows = customers.map { listOf(it.customerCode, it.fullName, it.phone, it.email, it.receiptCount.toString(), it.totalVerifiedAmount.toString()) }
                                    )
                                }
                                res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show() }
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Export CSV")
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    ReportExporter.exportXlsxAndShare(
                                        context = context,
                                        filePrefix = "${business.receiptPrefix}_Customers",
                                        sheetTitle = "Customers",
                                        headers = listOf("Code", "Full Name", "Phone", "Email", "Receipts", "Verified Spend (NGN)"),
                                        rows = customers.map { listOf(it.customerCode, it.fullName, it.phone, it.email, it.receiptCount.toString(), it.totalVerifiedAmount.toString()) }
                                    )
                                }
                                res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show() }
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Export Excel")
                        }
                    }
                }

                val filtered = remember(customers, search) {
                    val q = search.trim().lowercase()
                    if (q.isBlank()) customers
                    else customers.filter {
                        it.fullName.lowercase().contains(q) || it.phone.lowercase().contains(q) || it.email.lowercase().contains(q)
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { cust ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { viewingCustomer = cust },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cust.fullName, fontWeight = FontWeight.Bold)
                                    Text("${cust.phone.ifBlank { "No phone" }}  •  ${cust.receiptCount} receipt(s)", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("₦${"%,.2f".format(cust.totalVerifiedAmount)}", fontWeight = FontWeight.Bold, color = NigerianEmerald)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(8.dp))
                if (role.canManageCustomers()) {
                    Button(
                        onClick = { editingCatalog = CatalogItem() },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add Service or Product")
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(catalog, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { if (role.canManageCustomers()) editingCatalog = item },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.category.ifBlank { "General" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("₦${"%,.2f".format(item.unitPrice)}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
fun ReportsAnalyticsScreen(
    business: Business,
    role: StaffRole,
    receipts: List<ReceiptRecord>,
    effectiveEntitlements: EffectiveEntitlements? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusFilter by remember { mutableStateOf<VerificationStatus?>(null) }

    val filtered = remember(receipts, statusFilter) {
        if (statusFilter == null) receipts
        else receipts.filter { it.verificationStatus == statusFilter?.name }
    }

    val verifiedRevenue = receipts.filter { it.verificationStatus == VerificationStatus.VERIFIED.name }.sumOf { it.amountPaid }
    val pendingAmount = receipts.filter { it.verificationStatus == VerificationStatus.PENDING_VERIFICATION.name }.sumOf { it.amountPaid }
    val excludedCount = receipts.count {
        it.verificationStatus == VerificationStatus.REJECTED.name ||
            it.verificationStatus == VerificationStatus.REVERSED.name ||
            it.verificationStatus == VerificationStatus.CANCELLED.name
    }

    val headers = listOf("Receipt No", "Date", "Customer", "Purpose", "Bank", "Reference", "Amount (NGN)", "Status")
    val rows = filtered.map {
        listOf(
            it.receiptNumber,
            it.transactionDateSnapshot,
            it.customerName,
            it.paymentPurpose,
            it.senderBankSnapshot,
            it.transactionRefSnapshot,
            it.amountPaid.toString(),
            it.verificationStatus
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text("Financial Analytics & Reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Note: Verified Revenue strictly excludes pending, rejected, reversed, and cancelled claims.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                KpiMetricCard("Verified Revenue", "₦${"%,.2f".format(verifiedRevenue)}", "Confirmed bank inflow", NigerianEmerald, Modifier.weight(1f))
                KpiMetricCard("Pending Queue", "₦${"%,.2f".format(pendingAmount)}", "$excludedCount rejected/reversed", BurnishedGold, Modifier.weight(1f))
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("All (${receipts.size})") })
                VerificationStatus.entries.forEach { st ->
                    FilterChip(selected = statusFilter == st, onClick = { statusFilter = st }, label = { Text(st.label) })
                }
            }
        }
        if (role.canExportReports() && effectiveEntitlements?.reportsExportEnabled != false) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Export Filtered Report (${filtered.size} records)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.exportCsvAndShare(context, "${business.receiptPrefix}_Report", headers, rows)
                                        }
                                        res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                modifier = Modifier.testTag("export_csv_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("CSV")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.exportXlsxAndShare(context, "${business.receiptPrefix}_Report", "Receipts Report", headers, rows)
                                        }
                                        res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                modifier = Modifier.testTag("export_xlsx_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Excel (.xlsx)")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            ReportExporter.exportPdfReportAndShare(
                                                context = context,
                                                filePrefix = "${business.receiptPrefix}_Report",
                                                title = "${business.tradingName.ifBlank { business.registeredName }} — Payment Report",
                                                subtitle = "Verified Revenue: ₦${"%,.2f".format(verifiedRevenue)} | Records: ${filtered.size}",
                                                headers = headers,
                                                rows = rows
                                            )
                                        }
                                        res.onFailure { Toast.makeText(context, it.localizedMessage ?: "Export failed", Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                modifier = Modifier.testTag("export_pdf_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("PDF")
                            }
                        }
                    }
                }
            }
        }
        items(filtered, key = { it.id }) { r ->
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.receiptNumber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("${r.customerName} • ${r.senderBankSnapshot}", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("₦${"%,.2f".format(r.amountPaid)}", fontWeight = FontWeight.Bold)
                        VerificationStatusChip(r.verificationStatus)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
