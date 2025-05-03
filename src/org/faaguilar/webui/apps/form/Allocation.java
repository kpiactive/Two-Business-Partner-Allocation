package org.faaguilar.webui.apps.form;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Vector;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.minigrid.IMiniTable;
import org.compiere.model.MAllocationHdr;
import org.compiere.model.MAllocationLine;
import org.compiere.model.MDocType;
import org.compiere.model.MInvoice;
import org.compiere.model.MPayment;
import org.compiere.model.MSysConfig;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Msg;
import org.compiere.util.TimeUtil;
import org.compiere.util.Util;

/**
 * Clase independiente que combina la lógica de Allocation10
 * con el soporte de un segundo socio de negocio.
 *
 * El primer socio (m_C_BPartner_ID) se utiliza para los PAGOS.
 * El segundo socio (m_C_BPartner2_ID) para las FACTURAS.
 * 
 * No extiende de ninguna clase base.
 */
public class Allocation
{
    /** Logger */
    protected static final CLogger log = CLogger.getCLogger(Allocation.class);

    //  --- Campos principales de configuración ---
    /** Moneda de la asignación */
    protected int m_C_Currency_ID = 0;
    /** Cargo opcional (C_Charge_ID) */
    protected int m_C_Charge_ID = 0;
    /** Documento/Tipo de Documento */
    protected int m_C_DocType_ID = 0;

    /** Socio de Negocio 1: para los Pagos */
    protected int m_C_BPartner_ID = 0;
    /** Socio de Negocio 2: para las Facturas */
    protected int m_C_BPartner2_ID = 0;

    /** Organización en la que se hace la asignación */
    protected int m_AD_Org_ID = 0;

    //  --- Contadores y totales de la asignación ---
    protected int m_noInvoices = 0;
    protected int m_noPayments = 0;
    protected BigDecimal totalInv = Env.ZERO;
    protected BigDecimal totalPay = Env.ZERO;
    protected BigDecimal totalDiff = Env.ZERO;

    /** Formato para montos */
    protected DecimalFormat format = DisplayType.getNumberFormat(DisplayType.Amount);

    /** Fecha de asignación calculada (fecha más reciente) */
    protected Timestamp allocDate = null;

    /** Bandera que evita recálculos recursivos */
    private boolean m_calculating = false;

    /**
     * Maneja la verificación de "isAllocated" e "isPaid" en segundo plano.
     * Guardamos aquí los IDs de BPartners ya verificados.
     */
    protected ArrayList<Integer> m_bpartnerCheck = new ArrayList<Integer>();

    // --- Índices para las columnas en las tablas (para single o multi-currency) ---
    protected int i_payment = 7;   // Columna del monto aplicado en la tabla de pagos
    protected int i_open = 6;      // Columna del open amount en la tabla de facturas
    protected int i_discount = 7;
    protected int i_writeOff = 8;
    protected int i_applied = 9;
    protected int i_overUnder = 10;

    // ---------------------------------------------------------------------------
    // 1) Métodos "init" o de configuración.
    // ---------------------------------------------------------------------------

    /**
     * Inicializa valores por defecto (Moneda, etc.)
     * @throws Exception si falla algo en la inicialización
     */
    public void dynInit() throws Exception
    {
        // Moneda por defecto
        m_C_Currency_ID = Env.getContextAsInt(Env.getCtx(), Env.C_CURRENCY_ID);
        // Tipo de Documento por defecto para PaymentAllocation
        m_C_DocType_ID = MDocType.getDocType(MDocType.DOCBASETYPE_PaymentAllocation);
        // Organización
        m_AD_Org_ID = Env.getAD_Org_ID(Env.getCtx());
        if (log.isLoggable(Level.INFO)) {
            log.info("Currency=" + m_C_Currency_ID + ", DocType=" + m_C_DocType_ID);
        }
    }

    // ---------------------------------------------------------------------------
    // 2) Getters/Setters
    // ---------------------------------------------------------------------------
    public int getC_Currency_ID() {
        return m_C_Currency_ID;
    }
    public void setC_Currency_ID(int c_Currency_ID) {
        this.m_C_Currency_ID = c_Currency_ID;
    }

    public int getC_Charge_ID() {
        return m_C_Charge_ID;
    }
    public void setC_Charge_ID(int c_Charge_ID) {
        this.m_C_Charge_ID = c_Charge_ID;
    }

    public int getC_DocType_ID() {
        return m_C_DocType_ID;
    }
    public void setC_DocType_ID(int c_DocType_ID) {
        this.m_C_DocType_ID = c_DocType_ID;
    }

    public int getC_BPartner_ID() {
        return m_C_BPartner_ID;
    }
    public void setC_BPartner_ID(int c_BPartner_ID) {
        this.m_C_BPartner_ID = c_BPartner_ID;
    }

    public int getC_BPartner2_ID() {
        return m_C_BPartner2_ID;
    }
    public void setC_BPartner2_ID(int c_BPartner2_ID) {
        this.m_C_BPartner2_ID = c_BPartner2_ID;
    }

    public int getAD_Org_ID() {
        return m_AD_Org_ID;
    }
    public void setAD_Org_ID(int ad_Org_ID) {
        this.m_AD_Org_ID = ad_Org_ID;
    }

    public BigDecimal getInvoiceAppliedTotal() {
        return totalInv;
    }
    public BigDecimal getPaymentAppliedTotal() {
        return totalPay;
    }
    public BigDecimal getTotalDifference() {
        return totalDiff;
    }

    public int getSelectedInvoiceCount() {
        return m_noInvoices;
    }
    public int getSelectedPaymentCount() {
        return m_noPayments;
    }

    /**
     * ¿Está listo para asignar?
     * Devuelve true si la diferencia es cero o si hay cargo (para absorber la diferencia).
     */
    public boolean isOkToAllocate() {
        return totalDiff.signum() == 0 || getC_Charge_ID() > 0;
    }

    // ---------------------------------------------------------------------------
    // 3) Métodos de verificación y carga de datos
    // ---------------------------------------------------------------------------

    /**
     * Llama a checkBPartner1 (pagos) y checkBPartner2 (facturas).
     */
    public void checkBPartner() {
        // Para BP1
        checkBPartner1();
        // Para BP2
        checkBPartner2();
    }

    /**
     * Lógica interna para marcar isAllocated / isPaid en BP1 (pagos).
     */
    protected void checkBPartner1() {
        if (m_C_BPartner_ID == 0 || m_C_Currency_ID == 0)
            return;
        if (!m_bpartnerCheck.contains(m_C_BPartner_ID)) {
            if (log.isLoggable(Level.CONFIG)) {
                log.config("BPartner1=" + m_C_BPartner_ID + ", Currency=" + m_C_Currency_ID);
            }
            new Thread() {
                @Override
                public void run() {
                    MPayment.setIsAllocated(Env.getCtx(), m_C_BPartner_ID, null);
                    MInvoice.setIsPaid(Env.getCtx(), m_C_BPartner_ID, null);
                }
            }.start();
            m_bpartnerCheck.add(m_C_BPartner_ID);
        }
    }

    /**
     * Lógica interna para marcar isAllocated / isPaid en BP2 (facturas).
     */
    protected void checkBPartner2() {
        if (m_C_BPartner2_ID == 0 || m_C_Currency_ID == 0)
            return;
        if (!m_bpartnerCheck.contains(m_C_BPartner2_ID)) {
            if (log.isLoggable(Level.CONFIG)) {
                log.config("BPartner2=" + m_C_BPartner2_ID + ", Currency=" + m_C_Currency_ID);
            }
            new Thread() {
                @Override
                public void run() {
                    MPayment.setIsAllocated(Env.getCtx(), m_C_BPartner2_ID, null);
                    MInvoice.setIsPaid(Env.getCtx(), m_C_BPartner2_ID, null);
                }
            }.start();
            m_bpartnerCheck.add(m_C_BPartner2_ID);
        }
    }

    /**
     * Obtiene datos de pagos (solo para m_C_BPartner_ID).
     * @param isMultiCurrency si es multi-moneda
     * @param date Fecha de corte
     * @param trxName transacción
     * @return vector con los datos para poblar una tabla
     */
    public Vector<Vector<Object>> getPaymentData(boolean isMultiCurrency, Timestamp date, String trxName)
    {
        // Se apoya en método estático de MPayment
        // Filtra por m_C_BPartner_ID
        return MPayment.getUnAllocatedPaymentData(
            m_C_BPartner_ID,
            m_C_Currency_ID,
            isMultiCurrency,
            date,
            m_AD_Org_ID,
            trxName
        );
    }

    /**
     * Columnas para la tabla de pagos.
     */
    public Vector<String> getPaymentColumnNames(boolean isMultiCurrency)
    {
        Vector<String> columnNames = new Vector<String>();
        columnNames.add(Msg.getMsg(Env.getCtx(), "Select"));
        columnNames.add(Msg.translate(Env.getCtx(), "Date"));
        columnNames.add(Util.cleanAmp(Msg.translate(Env.getCtx(), "DocumentNo")));
        if (isMultiCurrency) {
            columnNames.add(Msg.getMsg(Env.getCtx(), "TrxCurrency"));
            columnNames.add(Msg.translate(Env.getCtx(), "Amount"));
        }
        columnNames.add(Msg.getMsg(Env.getCtx(), "ConvertedAmount"));
        columnNames.add(Msg.getMsg(Env.getCtx(), "OpenAmt"));
        columnNames.add(Msg.getMsg(Env.getCtx(), "AppliedAmt"));
        return columnNames;
    }

    /**
     * Ajusta la clase de cada columna en la tabla de pagos.
     * @param paymentTable referencia a la tabla
     * @param isMultiCurrency si es multi-moneda
     */
    public void setPaymentColumnClass(IMiniTable paymentTable, boolean isMultiCurrency)
    {
        int index = 0;
        paymentTable.setColumnClass(index++, Boolean.class, false);    // Select
        paymentTable.setColumnClass(index++, Timestamp.class, true);   // Date
        paymentTable.setColumnClass(index++, String.class, true);      // DocumentNo

        if (isMultiCurrency) {
            paymentTable.setColumnClass(index++, String.class, true);    // Currency
            paymentTable.setColumnClass(index++, BigDecimal.class, true);// PayAmt
        }
        paymentTable.setColumnClass(index++, BigDecimal.class, true);   // ConvertedAmt
        paymentTable.setColumnClass(index++, BigDecimal.class, true);   // OpenAmt
        paymentTable.setColumnClass(index++, BigDecimal.class, false);  // AppliedAmt

        // Ajusta i_payment al último
        i_payment = isMultiCurrency ? 7 : 5;
        paymentTable.autoSize();
    }

    /**
     * Obtiene datos de facturas (solo para m_C_BPartner2_ID).
     * @param isMultiCurrency si es multi-moneda
     * @param date Fecha de corte
     * @param trxName transacción
     * @return vector con datos de facturas
     */
    public Vector<Vector<Object>> getInvoiceData(boolean isMultiCurrency, Timestamp date, String trxName)
    {
        // Se apoya en método estático de MInvoice
        // Filtra por m_C_BPartner2_ID
        return MInvoice.getUnpaidInvoiceData(
            isMultiCurrency,
            date,
            m_AD_Org_ID,
            m_C_Currency_ID,
            m_C_BPartner2_ID,
            trxName
        );
    }

    /**
     * Columnas para la tabla de facturas.
     */
    public Vector<String> getInvoiceColumnNames(boolean isMultiCurrency)
    {
        Vector<String> columnNames = new Vector<String>();
        columnNames.add(Msg.getMsg(Env.getCtx(), "Select"));
        columnNames.add(Msg.translate(Env.getCtx(), "Date"));
        columnNames.add(Util.cleanAmp(Msg.translate(Env.getCtx(), "DocumentNo")));
        if (isMultiCurrency) {
            columnNames.add(Msg.getMsg(Env.getCtx(), "TrxCurrency"));
            columnNames.add(Msg.translate(Env.getCtx(), "Amount"));
        }
        columnNames.add(Msg.getMsg(Env.getCtx(), "ConvertedAmount"));
        columnNames.add(Msg.getMsg(Env.getCtx(), "OpenAmt"));
        columnNames.add(Msg.getMsg(Env.getCtx(), "Discount"));
        columnNames.add(Msg.getMsg(Env.getCtx(), "WriteOff"));
        columnNames.add(Msg.getMsg(Env.getCtx(), "AppliedAmt"));
        columnNames.add(Msg.getMsg(Env.getCtx(), "OverUnderAmt"));
        return columnNames;
    }

    /**
     * Ajusta la clase de cada columna para la tabla de facturas.
     */
    public void setInvoiceColumnClass(IMiniTable invoiceTable, boolean isMultiCurrency)
    {
        int index = 0;
        invoiceTable.setColumnClass(index++, Boolean.class, false);     // 0-Select
        invoiceTable.setColumnClass(index++, Timestamp.class, true);    // 1-Date
        invoiceTable.setColumnClass(index++, String.class, true);       // 2-DocumentNo
        if (isMultiCurrency) {
            invoiceTable.setColumnClass(index++, String.class, true);     // 3-Currency
            invoiceTable.setColumnClass(index++, BigDecimal.class, true); // 4-Amt
        }
        invoiceTable.setColumnClass(index++, BigDecimal.class, true);   // 5-ConvertedAmt
        invoiceTable.setColumnClass(index++, BigDecimal.class, true);   // 6-OpenAmt
        invoiceTable.setColumnClass(index++, BigDecimal.class, false);  // 7-Discount
        invoiceTable.setColumnClass(index++, BigDecimal.class, false);  // 8-WriteOff
        invoiceTable.setColumnClass(index++, BigDecimal.class, false);  // 9-Applied
        invoiceTable.setColumnClass(index++, BigDecimal.class, true);   // 10-OverUnder

        invoiceTable.autoSize();
    }

    // ---------------------------------------------------------------------------
    // 4) Cálculo de totales
    // ---------------------------------------------------------------------------

    /**
     * Según sea single/multi-currency, configura los índices de columna.
     */
    protected void prepareForCalculate(boolean isMultiCurrency)
    {
        i_open = isMultiCurrency ? 6 : 4;
        i_discount = isMultiCurrency ? 7 : 5;
        i_writeOff = isMultiCurrency ? 8 : 6;
        i_applied = isMultiCurrency ? 9 : 7;
        i_overUnder = isMultiCurrency ? 10 : 8;
    }

    /**
     * Lógica de "Write-off" o ajuste de la fila.
     * Se llama al modificar la tabla de pagos/facturas.
     */
    public String writeOff(int row, int col, boolean isInvoice,
                           IMiniTable payment, IMiniTable invoice,
                           boolean isAutoWriteOff)
    {
        String msg = "";
        if (m_calculating) {
            return msg;
        }
        m_calculating = true;

        if (log.isLoggable(Level.CONFIG)) {
            log.config("Row=" + row + ", Col=" + col + ", isInvoice=" + isInvoice);
        }

        // --- Ajuste en la tabla de Pagos ---
        if (!isInvoice) {
            BigDecimal open = (BigDecimal) payment.getValueAt(row, i_open);
            BigDecimal applied = (BigDecimal) payment.getValueAt(row, i_payment);

            if (col == 0) {
                // Selección
                if (((Boolean) payment.getValueAt(row, 0)).booleanValue()) {
                    applied = open;
                    // Ajustar si totalDiff es menor
                    if (totalDiff.abs().compareTo(applied.abs()) < 0
                        && totalDiff.signum() == -applied.signum())
                    {
                        applied = totalDiff.negate();
                    }
                } else {
                    applied = Env.ZERO;
                }
            }
            if (col == i_payment) {
                // Evitar invertir signo si no se permite Payment->CreditMemo
                if (!MSysConfig.getBooleanValue(MSysConfig.ALLOW_APPLY_PAYMENT_TO_CREDITMEMO, 
                        false, Env.getAD_Client_ID(Env.getCtx())))
                {
                    if (open.signum() > 0 && applied.signum() == -open.signum()) {
                        applied = applied.negate();
                    }
                }
                // No permitir sobrepasar el open
                if (!MSysConfig.getBooleanValue(MSysConfig.ALLOW_OVER_APPLIED_PAYMENT, 
                        false, Env.getAD_Client_ID(Env.getCtx())))
                {
                    if (open.abs().compareTo(applied.abs()) < 0) {
                        applied = open;
                    }
                }
            }
            payment.setValueAt(applied, row, i_payment);
        }
        // --- Ajuste en la tabla de Facturas ---
        else {
            boolean selected = ((Boolean) invoice.getValueAt(row, 0)).booleanValue();
            BigDecimal open = (BigDecimal) invoice.getValueAt(row, i_open);
            BigDecimal discount = (BigDecimal) invoice.getValueAt(row, i_discount);
            BigDecimal applied = (BigDecimal) invoice.getValueAt(row, i_applied);
            BigDecimal writeOff = (BigDecimal) invoice.getValueAt(row, i_writeOff);
            BigDecimal overUnder = (BigDecimal) invoice.getValueAt(row, i_overUnder);
            int openSign = open.signum();

            if (col == 0) {
                // Seleccionamos la factura => aplicamos todo
                if (selected) {
                    applied = open.subtract(discount);
                    writeOff = Env.ZERO;
                    overUnder = Env.ZERO;
                    // Ajustar con totalDiff
                    if (totalDiff.abs().compareTo(applied.abs()) < 0
                        && totalDiff.signum() == applied.signum())
                    {
                        applied = totalDiff;
                    }
                    if (isAutoWriteOff) {
                        writeOff = open.subtract(applied.add(discount));
                    } else {
                        overUnder = open.subtract(applied.add(discount));
                    }
                } else {
                    writeOff = Env.ZERO;
                    applied = Env.ZERO;
                    overUnder = Env.ZERO;
                }
            }

            // Ajustes al editar discount, writeOff o applied
            if (selected && col != 0) {
                // Todos deben tener el mismo signo que open, menos over/under
                if (discount.signum() == -openSign) discount = discount.negate();
                if (writeOff.signum() == -openSign) writeOff = writeOff.negate();
                if (applied.signum() == -openSign) applied = applied.negate();

                // discount y writeOff no pueden pasar de open
                if (discount.abs().compareTo(open.abs()) > 0) discount = open;
                if (writeOff.abs().compareTo(open.abs()) > 0) writeOff = open;

                // Regla 1) discount + writeOff < open
                BigDecimal newTotal = discount.add(writeOff).add(applied).add(overUnder);
                BigDecimal difference = newTotal.subtract(open);

                // Ajustar si discount+writeOff se pasan
                BigDecimal diffWOD = writeOff.add(discount).subtract(open);
                if (diffWOD.signum() == openSign) {
                    if (col == i_discount) {
                        writeOff = writeOff.subtract(diffWOD);
                    } else if (col == i_writeOff) {
                        discount = discount.subtract(diffWOD);
                    }
                    difference = difference.subtract(diffWOD);
                }
                // Regla 2) discount + writeOff + overUnder + applied = open
                if (col == i_applied) {
                    overUnder = overUnder.subtract(difference);
                } else {
                    applied = applied.subtract(difference);
                }
            }

            // Warning si writeOff > 30%
            if (isAutoWriteOff && 
                open.signum() != 0 && (writeOff.divide (open, 2, RoundingMode.HALF_UP).doubleValue() > 0.30))
            {
                msg = "AllocationWriteOffWarn";
            }

            invoice.setValueAt(discount, row, i_discount);
            invoice.setValueAt(applied, row, i_applied);
            invoice.setValueAt(writeOff, row, i_writeOff);
            invoice.setValueAt(overUnder, row, i_overUnder);
        }

        m_calculating = false;
        return msg;
    }

    // ---------------------------------------------------------------------------
    // 5) Métodos para cálculo final y guardado
    // ---------------------------------------------------------------------------

    /**
     * Calcula (pagos, facturas, diferencia).
     * @param paymentTable tabla con pagos
     * @param invoiceTable tabla con facturas
     * @param isMultiCurrency si está en multi-moneda
     */
    public void calculate(IMiniTable paymentTable, IMiniTable invoiceTable, boolean isMultiCurrency)
    {
        // Ajusta índices
        prepareForCalculate(isMultiCurrency);

        // 1) Calcula pagos
        calculatePayment(paymentTable, isMultiCurrency);

        // 2) Calcula facturas
        calculateInvoice(invoiceTable, isMultiCurrency);

        // 3) Calcula diferencia
        calculateDifference();
    }

    /**
     * Calcula total de pagos seleccionados.
     */
    public String calculatePayment(IMiniTable payment, boolean isMultiCurrency)
    {
        totalPay = Env.ZERO;
        m_noPayments = 0;
        int rows = payment.getRowCount();
        for (int i = 0; i < rows; i++) {
            if (((Boolean)payment.getValueAt(i, 0)).booleanValue()) {
                Timestamp ts = (Timestamp) payment.getValueAt(i, 1);
                if (!isMultiCurrency) {
                    allocDate = TimeUtil.max(allocDate, ts);
                }
                BigDecimal bd = (BigDecimal) payment.getValueAt(i, i_payment);
                totalPay = totalPay.add(bd);
                m_noPayments++;
                if (log.isLoggable(Level.FINE)) {
                    log.fine("Payment_" + i + " = " + bd + " => TotalPay=" + totalPay);
                }
            }
        }
        return getPaymentInfoText();
    }

    /**
     * Mensaje/resumen de pagos
     */
    public String getPaymentInfoText() {
        return m_noPayments + " - " + Msg.getMsg(Env.getCtx(), "Sum") + " " + format.format(totalPay);
    }

    /**
     * Calcula total de facturas seleccionadas.
     */
    public String calculateInvoice(IMiniTable invoice, boolean isMultiCurrency)
    {
        totalInv = Env.ZERO;
        m_noInvoices = 0;
        int rows = invoice.getRowCount();
        for (int i = 0; i < rows; i++) {
            if (((Boolean)invoice.getValueAt(i, 0)).booleanValue()) {
                Timestamp ts = (Timestamp)invoice.getValueAt(i, 1);
                if (!isMultiCurrency) {
                    allocDate = TimeUtil.max(allocDate, ts);
                }
                BigDecimal bd = (BigDecimal)invoice.getValueAt(i, i_applied);
                totalInv = totalInv.add(bd);
                m_noInvoices++;
                if (log.isLoggable(Level.FINE)) {
                    log.fine("Invoice_" + i + " = " + bd + " => TotalInv=" + totalInv);
                }
            }
        }
        return getInvoiceInfoText();
    }

    /**
     * Mensaje/resumen de facturas
     */
    public String getInvoiceInfoText() {
        return m_noInvoices + " - " + Msg.getMsg(Env.getCtx(), "Sum") + " " + format.format(totalInv);
    }

    /**
     * Calcula la diferencia entre total de pagos y total de facturas.
     */
    public void calculateDifference() {
        totalDiff = totalPay.subtract(totalInv);
    }

    // ---------------------------------------------------------------------------
    // 6) Guardar (crear AllocationHdr + lines)
    // ---------------------------------------------------------------------------

    /**
     * Procesa y guarda la asignación (AllocationHdr + Lines) en la BD.
     *
     * @param windowNo número de ventana
     * @param dateTrx fecha para la asignación
     * @param payment tabla de pagos (BP1)
     * @param invoice tabla de facturas (BP2)
     * @param trxName nombre de la transacción
     * @return MAllocationHdr generado
     */
    public MAllocationHdr saveData(int windowNo, Timestamp dateTrx,
                                   IMiniTable payment, IMiniTable invoice,
                                   String trxName)
    {
        if (m_noInvoices + m_noPayments == 0) {
            return null;
        }
        // Verificamos org
        int AD_Client_ID = Env.getContextAsInt(Env.getCtx(), windowNo, "AD_Client_ID");
        int AD_Org_ID = Env.getContextAsInt(Env.getCtx(), windowNo, "AD_Org_ID");
        if (AD_Org_ID == 0) {
            throw new AdempiereException("@Org0NotAllowed@");
        }

        // Preparamos AllocationHdr
        MAllocationHdr alloc = new MAllocationHdr(
            Env.getCtx(),
            true, // manual
            dateTrx,
            m_C_Currency_ID,
            Env.getContext(Env.getCtx(), Env.AD_USER_NAME),
            trxName
        );
        alloc.setAD_Org_ID(AD_Org_ID);
        alloc.setC_DocType_ID(m_C_DocType_ID);
        alloc.setDescription("Asignación Pagos(BP1=" + m_C_BPartner_ID
                + ") a Facturas(BP2=" + m_C_BPartner2_ID + ")");
        alloc.saveEx(trxName);

        // --- 1) Recopilamos pagos (BP1) ---
        int pRows = payment.getRowCount();
        ArrayList<Integer> paymentList = new ArrayList<Integer>(pRows);
        ArrayList<BigDecimal> amountList = new ArrayList<BigDecimal>(pRows);
        for (int i = 0; i < pRows; i++) {
            if (((Boolean)payment.getValueAt(i, 0)).booleanValue()) {
                KeyNamePair pp = (KeyNamePair) payment.getValueAt(i, 2);
                int C_Payment_ID = pp.getKey();
                paymentList.add(C_Payment_ID);

                BigDecimal payAmt = (BigDecimal) payment.getValueAt(i, i_payment);
                amountList.add(payAmt);
            }
        }

        // --- 2) Procesamos facturas (BP2) ---
        int iRows = invoice.getRowCount();
        BigDecimal unmatchedApplied = Env.ZERO;  // Para controlar sobrantes
        for (int i = 0; i < iRows; i++) {
            if (((Boolean)invoice.getValueAt(i, 0)).booleanValue()) {
                KeyNamePair pp = (KeyNamePair)invoice.getValueAt(i, 2);
                int C_Invoice_ID = pp.getKey();

                BigDecimal AppliedAmt = (BigDecimal)invoice.getValueAt(i, i_applied);
                BigDecimal DiscountAmt = (BigDecimal)invoice.getValueAt(i, i_discount);
                BigDecimal WriteOffAmt = (BigDecimal)invoice.getValueAt(i, i_writeOff);
                BigDecimal OverUnderAmt = 
                        ((BigDecimal)invoice.getValueAt(i, i_open))
                            .subtract(AppliedAmt)
                            .subtract(DiscountAmt)
                            .subtract(WriteOffAmt);

                // Recorremos los pagos
                for (int j = 0; j < paymentList.size() && AppliedAmt.signum() != 0; j++) {
                    int C_Payment_ID = paymentList.get(j);
                    BigDecimal PaymentAmt = amountList.get(j);

                    // Mismo signo => se puede aplicar
                    if (PaymentAmt.signum() == AppliedAmt.signum()) {
                        BigDecimal amount = AppliedAmt;
                        if (amount.abs().compareTo(PaymentAmt.abs()) > 0) {
                            amount = PaymentAmt;
                        }
                        // Creamos la línea
                        MAllocationLine aLine = new MAllocationLine(alloc,
                            amount, DiscountAmt, WriteOffAmt, OverUnderAmt);
                        // setDocInfo => El BPartner de la Factura es BP2
                        aLine.setDocInfo(m_C_BPartner2_ID, 0, C_Invoice_ID);
                        // setPaymentInfo => El pago es BP1
                        aLine.setPaymentInfo(C_Payment_ID, 0);
                        aLine.saveEx(trxName);

                        // Ajustamos
                        DiscountAmt = Env.ZERO;
                        WriteOffAmt = Env.ZERO;
                        AppliedAmt = AppliedAmt.subtract(amount);
                        PaymentAmt = PaymentAmt.subtract(amount);
                        amountList.set(j, PaymentAmt);
                    }
                }

                // Si no se asignó por completo, creamos una línea con lo que sobra
                if (AppliedAmt.signum() != 0 || DiscountAmt.signum() != 0 || WriteOffAmt.signum() != 0) {
                    MAllocationLine aLine = new MAllocationLine(
                            alloc,
                            AppliedAmt,
                            DiscountAmt,
                            WriteOffAmt,
                            OverUnderAmt
                    );
                    aLine.setDocInfo(m_C_BPartner2_ID, 0, C_Invoice_ID);
                    aLine.setPaymentInfo(0, 0); // sin pago
                    aLine.saveEx(trxName);

                    unmatchedApplied = unmatchedApplied.add(AppliedAmt);
                }
            }
        }

        // --- 3) Pagos sobrantes (no usados en facturas) ---
        for (int i = 0; i < paymentList.size(); i++) {
            BigDecimal payAmt = amountList.get(i);
            if (payAmt.signum() == 0) {
                continue;
            }
            int C_Payment_ID = paymentList.get(i);
            MAllocationLine aLine = new MAllocationLine(alloc,
                    payAmt, Env.ZERO, Env.ZERO, Env.ZERO);
            // Ese pago es del BP1
            aLine.setDocInfo(m_C_BPartner_ID, 0, 0);
            aLine.setPaymentInfo(C_Payment_ID, 0);
            aLine.saveEx(trxName);
            unmatchedApplied = unmatchedApplied.subtract(payAmt);
        }

        // --- 4) Cargo => absorbe totalDiff ---
        if (m_C_Charge_ID > 0 && getTotalDifference().compareTo(Env.ZERO) != 0) {
            BigDecimal chargeAmt = getTotalDifference();
            MAllocationLine aLine = new MAllocationLine(alloc, 
                chargeAmt.negate(), Env.ZERO, Env.ZERO, Env.ZERO);
            aLine.setC_Charge_ID(m_C_Charge_ID);
            // El BPartner en la línea de cargo se podría asociar a BP2 o a ambos 
            aLine.setC_BPartner_ID(m_C_BPartner2_ID);  
            if (!aLine.save(trxName)) {
                throw new AdempiereException("No se pudo guardar línea de cargo (Charge=" + m_C_Charge_ID + ")");
            }
            unmatchedApplied = unmatchedApplied.add(chargeAmt);
        }

        if (unmatchedApplied.signum() != 0) {
            throw new AdempiereException("La asignación no quedó balanceada (sobrante/faltante=" + unmatchedApplied + ")");
        }

        // Completar el Allocation
        if (alloc.get_ID() != 0) {
            if (!alloc.processIt(DocAction.ACTION_Complete)) {
                throw new AdempiereException(
                    "No se pudo completar la asignación: " + alloc.getProcessMsg()
                );
            }
            alloc.saveEx(trxName);
        }

        // Actualizar isPaid en facturas
        int iRows2 = invoice.getRowCount();
        for (int i = 0; i < iRows2; i++) {
            if (((Boolean)invoice.getValueAt(i, 0)).booleanValue()) {
                KeyNamePair pp = (KeyNamePair)invoice.getValueAt(i, 2);
                int C_Invoice_ID = pp.getKey();
                String sql = "SELECT invoiceOpen(C_Invoice_ID,0) FROM C_Invoice WHERE C_Invoice_ID=?";
                BigDecimal open = DB.getSQLValueBD(trxName, sql, C_Invoice_ID);
                if (open != null && open.signum() == 0) {
                    sql = "UPDATE C_Invoice SET IsPaid='Y' WHERE C_Invoice_ID=" + C_Invoice_ID;
                    DB.executeUpdate(sql, trxName);
                }
            }
        }

        // Actualizar isAllocated en pagos
        for (int i = 0; i < paymentList.size(); i++) {
            MPayment pay = new MPayment(Env.getCtx(), paymentList.get(i), trxName);
            if (pay.testAllocation()) {
                pay.saveEx(trxName);
            }
        }
        paymentList.clear();
        amountList.clear();

        return alloc;
    }
}
