// 등급 색. 모르는 등급은 브랜드색으로 보여 화면이 깨지지 않게 한다.
const GRADE_COLORS: Record<string, string> = {
  VIP: 'var(--seat-vip)',
  R: 'var(--seat-r)',
  S: 'var(--seat-s)',
  A: 'var(--seat-a)',
}

export function gradeColor(grade: string): string {
  return GRADE_COLORS[grade] ?? 'var(--primary-600)'
}
