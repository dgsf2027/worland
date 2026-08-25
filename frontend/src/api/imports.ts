import request from '@/utils/request'

export interface ImportField {
  key: string
  label: string
  required: boolean
}

export interface TemplateResp {
  targetType: string
  label: string
  fields: ImportField[]
  dedupKeyLabel: string
}

export interface RowResult {
  rowNo: number
  status: string
  message: string
  escaped: boolean
  data: Record<string, string>
}

export interface PreviewResp {
  jobId: number
  no: string
  targetType: string
  fileName: string
  fileSize: number
  totalRows: number
  okRows: number
  dupRows: number
  errRows: number
  escapedCells: number
  headers: string[]
  rows: RowResult[]
  status: string
}

export interface CommitResp {
  jobId: number
  imported: number
  skippedDup: number
  failed: number
  failures: string[]
}

export interface JobRow {
  id: number
  no: string
  targetType: string
  fileName: string
  totalRows: number
  okRows: number
  dupRows: number
  errRows: number
  escapedCells: number
  importedRows: number
  status: string
  operatorName: string
  createTime: string
  committedAt?: string
}

export const importApi = {
  template: (target: string) => request.get<any, TemplateResp>('/rent/imports/template', { params: { target } }),
  preview: (target: string, file: File, mapping?: Record<string, string>, projectId?: number) => {
    const fd = new FormData()
    fd.append('target', target)
    fd.append('file', file)
    if (mapping) fd.append('mapping', JSON.stringify(mapping))
    if (projectId != null) fd.append('projectId', String(projectId))
    return request.post<any, PreviewResp>('/rent/imports/preview', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  /** 下载空白模板 .xlsx(表头=目标字段 label,填完可直接回传预览) */
  downloadTemplate: async (target: string) => {
    const resp: any = await request.get('/rent/imports/template/download', {
      params: { target },
      responseType: 'blob',
    })
    const url = URL.createObjectURL(resp.data)
    const a = document.createElement('a')
    a.href = url
    a.download = `导入模板-${target}.xlsx`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  },
  commit: (jobId: number) => request.post<any, CommitResp>(`/rent/imports/${jobId}/commit`),
  list: (target?: string, status?: string) =>
    request.get<any, JobRow[]>('/rent/imports', { params: { target, status } }),
}
