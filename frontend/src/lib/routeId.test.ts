import { describe, expect, it } from 'vitest'
import { readRouteId } from '@/lib/routeId'

describe('readRouteId', () => {
  it('양의 10진 정수 문자열은 숫자로 읽는다', () => {
    expect(readRouteId('7')).toBe(7)
  })

  it.each(['0', '-1', '0x10', '1e3', 'abc', '', '1.5', ' 7', '99999999999999999999'])(
    '%j는 null이다',
    (value) => {
      expect(readRouteId(value)).toBeNull()
    },
  )

  it('값이 없으면 null이다', () => {
    expect(readRouteId(undefined)).toBeNull()
  })
})
