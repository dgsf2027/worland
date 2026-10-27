import { ElMessage } from 'element-plus'
import { checkUploadFile } from '@/api/asset'
import { recognizePcDoc, type RecognizeKind } from '@/api/purchaseContract'
import { ExtractError, extractForRecognize, RECOGNIZE_EXTS } from '@/utils/docExtract'

/** 能识别的文件类型提示，给上传控件的 accept / 说明文字用 */
export const RECOGNIZE_ACCEPT = 'image/*,application/pdf,.pdf,.txt,text/plain'

/**
 * 把用户选的文件(图片 / PDF / txt)提取成「图片 + 文字」并调用识别接口，返回识别出的字段；
 * 失败返回 null：请求错误已由 axios 拦截器弹出，本地读取/解析错误在这里提示。识别失败不影响手工录入。
 */
export async function recognizeFromFiles(kind: RecognizeKind, files: File[]): Promise<any | null> {
  for (const f of files) {
    const err = checkUploadFile(f, RECOGNIZE_EXTS, 10)
    if (err) { ElMessage.warning(err + '（可识别：图片 / PDF / txt）'); return null }
  }
  try {
    const ex = await extractForRecognize(files)
    ex.notes.forEach((n) => ElMessage.info(n))
    return await recognizePcDoc(kind, ex.images, ex.text || undefined)
  } catch (err: any) {
    if (err instanceof ExtractError) ElMessage.warning(err.message)
    return null
  }
}
