const ArtifactMessages = Object.freeze({
  channel: 'dumphere-artifact-v1',

  accepts(event, source, origin) {
    return event.source === source && event.origin === origin &&
      event.data?.channel === this.channel && typeof event.data.type === 'string';
  },

  send(target, origin, type, payload = {}) {
    target?.postMessage({ channel: this.channel, type, payload }, origin);
  },

  isXpath(value) {
    return typeof value === 'string' && value.startsWith('/') && value.length <= 4096;
  },

  isAnchor(value) {
    return value && this.isXpath(value.xpath) &&
      Number.isFinite(value.relativeX) && value.relativeX >= 0 && value.relativeX <= 1 &&
      Number.isFinite(value.relativeY) && value.relativeY >= 0 && value.relativeY <= 1;
  },

  isBounds(value) {
    return value && ['left', 'top', 'width', 'height'].every(key => Number.isFinite(value[key])) &&
      value.width >= 0 && value.height >= 0;
  }
});
