export const DEFAULT_USER_AVATAR =
  'https://upload.wikimedia.org/wikipedia/commons/b/bf/West-highland-white-terrier-dog.jpg'

export const getUserAvatar = (userAvatar?: string) => {
  return userAvatar?.trim() || DEFAULT_USER_AVATAR
}
