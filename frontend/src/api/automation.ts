import request from '@/utils/request'

export interface AutomationRule {
  id: number
  ruleKey: string
  ruleName: string
  category: string
  scheduleDesc?: string
  enabled: number
  leadDays?: number
  thresholdAmount?: number
  targetRole?: string
  description?: string
  lastRunAt?: string
  lastStatus?: string
  lastSummary?: string
}

export interface NotifyChannel {
  id: number
  name: string
  channelType: 'wecom' | 'wecom_app' | 'dingtalk' | 'feishu' | 'generic'
  webhookUrl: string
  secret?: string
  targetRole: string
  enabled: number
  lastSentAt?: string
  lastError?: string
}

export interface AutomationLog {
  id: number
  ruleKey: string
  ruleName: string
  triggerSource: string
  status: string
  affectedCount: number
  pushedCount: number
  summary?: string
  detailText?: string
  durationMs: number
  executedAt: string
}

export interface RunRuleResult {
  ruleKey: string
  ruleName: string
  status: string
  affectedCount: number
  pushedCount: number
  summary: string
  previewText?: string
  durationMs: number
}

export interface CollectionNotice {
  billId: number
  billNo: string
  contractId?: number
  contractNo: string
  customerName: string
  periodNo?: number
  dueDate: string
  overdueDays: number
  level: string
  unpaidAmount: number
  estimatedPenalty: number
  totalDue: number
  noticeText: string
}

export interface BankStatement {
  id: number
  batchNo: string
  txDate: string
  counterpartyName?: string
  amount: number
  direction: 'IN' | 'OUT'
  bankSerial?: string
  memo?: string
  matchStatus: string
  matchType?: string
  matchedBizId?: number
  matchedBizNo?: string
  matchedCustomer?: string
  confidence: number
  matchReason?: string
  reconciledAt?: string
  operatorName?: string
}

export interface UnpaidBillOption {
  billId: number
  billNo: string
  contractNo: string
  customerName: string
  periodNo?: number
  dueDate?: string
  remainingAmount: number
  status: string
}

export function fetchAutomationRules(): Promise<AutomationRule[]> {
  return request.get('/rent/automation/rules')
}

export function updateAutomationRule(
  ruleKey: string,
  body: { enabled?: boolean; leadDays?: number | null; thresholdAmount?: number | null; targetRole?: string },
): Promise<AutomationRule> {
  return request.put(`/rent/automation/rules/${ruleKey}`, body)
}

export function runAutomationRule(ruleKey: string): Promise<RunRuleResult> {
  return request.post(`/rent/automation/rules/${ruleKey}/run`)
}

export function fetchNotifyChannels(): Promise<NotifyChannel[]> {
  return request.get('/rent/automation/channels')
}

export function saveNotifyChannel(body: Record<string, any>): Promise<NotifyChannel> {
  return request.post('/rent/automation/channels', body)
}

export function deleteNotifyChannel(id: number): Promise<void> {
  return request.delete(`/rent/automation/channels/${id}`)
}

export function testNotifyChannel(id: number): Promise<string> {
  return request.post(`/rent/automation/channels/${id}/test`)
}

export function fetchAutomationLogs(params?: { ruleKey?: string; limit?: number }): Promise<AutomationLog[]> {
  return request.get('/rent/automation/logs', { params })
}

export function fetchCollectionNotices(leadDays?: number): Promise<CollectionNotice[]> {
  return request.get('/rent/automation/collection-notices', { params: leadDays != null ? { leadDays } : undefined })
}

export function fetchBankStatements(matchStatus?: string): Promise<BankStatement[]> {
  return request.get('/rent/automation/bank-statements', { params: matchStatus ? { matchStatus } : undefined })
}

export function fetchUnpaidBillOptions(): Promise<UnpaidBillOption[]> {
  return request.get('/rent/automation/bank-statements/unpaid-bills')
}

export function importBankStatementFile(file: File): Promise<{
  batchNo: string
  totalRows: number
  importedRows: number
  duplicateRows: number
  matchedRows: number
  needConfirmRows: number
}> {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/rent/automation/bank-statements/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000,
  })
}

export function submitManualBankStatements(rows: Record<string, any>[]): Promise<{
  batchNo: string
  totalRows: number
  importedRows: number
  duplicateRows: number
  matchedRows: number
  needConfirmRows: number
}> {
  return request.post('/rent/automation/bank-statements/manual', rows)
}

export function reassignBankStatement(id: number, billId: number | null): Promise<BankStatement> {
  return request.put(`/rent/automation/bank-statements/${id}/reassign`, { billId })
}

export function ignoreBankStatement(id: number): Promise<void> {
  return request.post(`/rent/automation/bank-statements/${id}/ignore`)
}

export function reconcileBankStatements(statementIds: number[]): Promise<{
  requested: number
  succeeded: number
  failed: number
  messages: string[]
}> {
  return request.post('/rent/automation/bank-statements/reconcile', { statementIds })
}
