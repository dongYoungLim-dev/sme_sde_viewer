import { useEffect, useRef, useState } from 'react'
import Icon from '../../ui/Icon.jsx'
import { api } from '../../api/endpoints.js'
import { useSession } from '../../session/SessionContext.jsx'
import { fmtDT } from '../../domain/format.js'

/**
 * SME 첨부파일 패널 (`UR-260922-1`) — 목록·업로드·다운로드·삭제.
 *
 * ⚠️ **ITSM 첨부(`attachments`, 항상 빈 스텁)와 완전히 다른 데이터다** — 이건 SME 가 이 보드에
 * 직접 올린 파일이다. 업로드 가능 여부(`canUpload`)·허용 형식·용량은 서버가 계산해서 그대로 주므로
 * 여기서 역할을 다시 판정하지 않는다(§8-11 SME 전용, §8-12 형식·용량 — 전부 서버 값을 그대로 쓴다).
 *
 * 목록은 `DetailResponse.files` 를 쓰지 않고 **이 컴포넌트가 직접 읽는다** — 그래야 `canUpload`·
 * `uploadBlockedReason`·`maxFiles`·`maxBytes`·`allowedExtensions` 같은 업로드 정책까지 같이 온다.
 */
export default function AttachmentPanel({ reqNo, onChanged }) {
  const { run } = useSession()
  const [data, setData] = useState(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const inputRef = useRef(null)

  const load = () => run(() => api.files(reqNo), { silent: true }).then(setData).catch(() => {})

  useEffect(() => {
    setData(null)
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reqNo])

  const onFile = async (e) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setErr(''); setBusy(true)
    try {
      await run(() => api.uploadFile(reqNo, file), { silent: true })
      await load(); onChanged?.()
    } catch (er) { setErr(er?.body?.message || er?.message || '업로드에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const onDelete = async (id) => {
    setErr(''); setBusy(true)
    try {
      await run(() => api.deleteFile(id), { silent: true })
      await load(); onChanged?.()
    } catch (er) { setErr(er?.body?.message || er?.message || '삭제에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const onDownload = (f) => { run(() => api.downloadFile(f.id, f.fileName), { silent: true }).catch(() => {}) }

  if (!data) return <p className="att-empty">불러오는 중…</p>

  return (
    <div>
      {err && <div className="dw-note done-note"><Icon name="warn" /><span>{err}</span></div>}
      {data.files.length
        ? <ul className="att-list">
          {data.files.map(f => (
            <li className="att" key={f.id}>
              <button type="button" className="att-link" onClick={() => onDownload(f)} title="다운로드">
                <Icon name="file" /><span className="an">{f.fileName}</span>
                <span className="as mono">{f.fileSize ? Math.round(f.fileSize / 1024) + ' KB' : ''}</span>
              </button>
              <span className="att-meta">{f.uploadedByName} · {fmtDT(f.uploadedAt)}</span>
              {f.canDelete &&
                <button type="button" className="att-del" disabled={busy} title="삭제"
                  onClick={() => onDelete(f.id)} aria-label={`${f.fileName} 삭제`}><Icon name="close" /></button>}
            </li>
          ))}
        </ul>
        : <p className="att-empty">첨부된 파일이 없습니다.</p>}
      <input ref={inputRef} type="file" hidden onChange={onFile}
        accept={data.allowedExtensions.map(e => '.' + e).join(',')} />
      {data.canUpload
        ? <button type="button" className="btn-view soft att-add" disabled={busy}
          onClick={() => inputRef.current?.click()}><Icon name="file" /> 파일 올리기</button>
        : <p className="field-hint">{data.uploadBlockedReason}</p>}
      <p className="field-hint">엑셀·워드·PPT·이미지 · 건당 {Math.round(data.maxBytes / 1024 / 1024)}MB ·
        요청당 최대 {data.maxFiles}개</p>
    </div>
  )
}
