import { cn as mergeClasses, type ClassValue } from 'cn'

// type-* 글자 토큰(index.css의 @utility)은 글자 크기·줄 높이·굵기를 한꺼번에 바꾼다.
const TYPE_TOKEN = /^type-/
const TYPOGRAPHY_CLASS =
  /^(?:type-\S+|text-(?:xs|sm|base|lg|xl|[2-9]xl|\[\d[^\]]*\])|leading-\S+|font-(?:thin|extralight|light|normal|medium|semibold|bold|extrabold|black))$/

// Note: cn 패키지는 type-*를 모른다. 그대로 두면 앞뒤 type-*나 text-sm·leading-*·font-*가 함께 남고,
// 어느 쪽이 이길지가 클래스 순서가 아니라 CSS 생성 순서(속성 수, 알파벳)로 정해진다.
// cn/config의 createCn으로 알려 주면 번들이 45 kB 늘어서, 마지막 type-* 앞의 같은 속성 클래스만 지운다.
// 변형이 붙은 클래스(md:·hover:)는 건드리지 않는다.
export function cn(...inputs: ClassValue[]): string {
  const classes = mergeClasses(...inputs)
    .split(' ')
    .filter((name) => name !== '')
  const lastTypeIndex = classes.findLastIndex((name) => TYPE_TOKEN.test(name))
  if (lastTypeIndex === -1) {
    return classes.join(' ')
  }
  return classes
    .filter((name, index) => index >= lastTypeIndex || !TYPOGRAPHY_CLASS.test(name))
    .join(' ')
}
