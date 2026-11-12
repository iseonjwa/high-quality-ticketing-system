// 지금 주소를 다시 불러온다. jsdom의 location.reload는 바꿔 끼울 수 없어, 테스트가 이 모듈을 대신한다
export function reloadPage() {
  window.location.reload()
}
