import { describe, expect, it } from 'vitest'
import { safeHref } from './safeHref'

describe('safeHref', () => {
  it('keeps http and https links', () => {
    expect(safeHref('https://www.ebay.com/itm/123')).toBe('https://www.ebay.com/itm/123')
    expect(safeHref('http://localhost:9000/photo.jpg?sig=x')).toBe('http://localhost:9000/photo.jpg?sig=x')
  })

  it('drops script, data and other schemes', () => {
    expect(safeHref('javascript:alert(1)')).toBeUndefined()
    expect(safeHref(' JavaScript:alert(1)')).toBeUndefined()
    expect(safeHref('data:text/html,<script>alert(1)</script>')).toBeUndefined()
    expect(safeHref('vbscript:msgbox')).toBeUndefined()
  })

  it('drops relative, malformed and empty values', () => {
    expect(safeHref('/api/admin')).toBeUndefined()
    expect(safeHref('www.ebay.com/itm/123')).toBeUndefined()
    expect(safeHref('not a url')).toBeUndefined()
    expect(safeHref('')).toBeUndefined()
    expect(safeHref(null)).toBeUndefined()
  })
})
