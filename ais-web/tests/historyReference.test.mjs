import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildReuseAttachmentRequest,
  selectHistorySourceUrl,
  stripAppBasePath,
} from '../src/utils/historyReference.ts'

test('selectHistorySourceUrl selects the image displayed by the quality choice', () => {
  const item = {
    url: '/ais/api/images/42.png',
    thumbUrl: '/ais/api/images/42/thumbnail?size=small',
  }

  assert.equal(selectHistorySourceUrl(item, false), item.thumbUrl)
  assert.equal(selectHistorySourceUrl(item, true), item.url)
})

test('selectHistorySourceUrl falls back to original', () => {
  assert.equal(selectHistorySourceUrl({ url: '/api/attachments/9.png' }, false), '/api/attachments/9.png')
})

test('stripAppBasePath handles images and attachments', () => {
  assert.equal(stripAppBasePath('/ais/api/images/42.png', '/ais/'), '/api/images/42.png')
  assert.equal(
    stripAppBasePath('/ais/api/attachments/9/thumbnail?size=small', '/ais/'),
    '/api/attachments/9/thumbnail?size=small',
  )
})

test('buildReuseAttachmentRequest preserves URL and metadata', () => {
  assert.deepEqual(
    buildReuseAttachmentRequest(
      '/ais/api/attachments/9/thumbnail?size=small',
      '/ais/',
      'history-12.webp',
      'image/webp',
    ),
    {
      fileUrl: '/api/attachments/9/thumbnail?size=small',
      originalName: 'history-12.webp',
      contentType: 'image/webp',
    },
  )
})
