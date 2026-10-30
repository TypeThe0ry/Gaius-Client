// Flags issues and comments that look like they share game files, builds or
// download links, so a maintainer can remove them. It only labels and
// comments; it never deletes anything. See CONTRIBUTING.md.

const FILE_EXTENSIONS = [
  'jar', 'zip', '7z', 'rar', 'tar', 'gz', 'tgz', 'xz',
  'html', 'htm', 'wasm', 'js', 'ogg', 'mcpack', 'mcworld',
];

const FILE_HOSTS = [
  'mediafire.com', 'mega.nz', 'mega.io', 'drive.google.com', 'docs.google.com',
  'dropbox.com', 'dropboxusercontent.com', '1drv.ms', 'onedrive.live.com',
  'sharepoint.com', 'gofile.io', 'pixeldrain.com', 'catbox.moe', 'litter.catbox.moe',
  'transfer.sh', 'wetransfer.com', 'we.tl', 'sendspace.com', 'workupload.com',
  'krakenfiles.com', 'anonfiles.com', 'file.io', 'pan.baidu.com', 'lanzoux.com',
  'lanzoui.com', 'lanzouw.com', 'lanzout.com', '123pan.com', 'aliyundrive.com',
  'alipan.com', 'quark.cn',
];

const SITE_HOSTS = [
  'github.io', 'pages.dev', 'netlify.app', 'vercel.app', 'glitch.me',
  'replit.app', 'repl.co', 'surge.sh', 'neocities.org', 'web.app', 'firebaseapp.com',
];

const REQUEST_PATTERNS = [
  /\bdownload\s+link/i,
  /\bwhere\s+(can\s+i|do\s+i|to)\s+(download|get|play)\b/i,
  /\b(send|give|share|upload)\s+(me\s+)?(the\s+)?(build|file|html|jar|link)\b/i,
  /\bunblocked\b/i,
  /下载(链接|地址)/,
  /求(下载|链接|资源|分享)/,
  /网盘/,
];

const URL_PATTERN = /\bhttps?:\/\/[^\s<>()"'`\]]+/gi;

function hostMatches(host, list) {
  return list.some((entry) => host === entry || host.endsWith('.' + entry));
}

function scan(text) {
  const findings = [];
  const body = String(text || '');
  for (const raw of body.match(URL_PATTERN) || []) {
    let url;
    try {
      url = new URL(raw.replace(/[.,;:!?]+$/, ''));
    } catch {
      continue;
    }
    const host = url.hostname.toLowerCase();
    let path = url.pathname.toLowerCase();
    try {
      path = decodeURIComponent(path);
    } catch {
      // keep the encoded path
    }
    const extension = path.includes('.') ? path.slice(path.lastIndexOf('.') + 1) : '';
    if (hostMatches(host, FILE_HOSTS)) {
      findings.push(`file-sharing link: ${host}`);
    } else if (FILE_EXTENSIONS.includes(extension)) {
      findings.push(`link to a .${extension} file`);
    } else if (hostMatches(host, SITE_HOSTS)) {
      findings.push(`link to a hosted site: ${host}`);
    }
  }
  if (REQUEST_PATTERNS.some((pattern) => pattern.test(body))) {
    findings.push('looks like a request for a download or build');
  }
  return [...new Set(findings)];
}

const LABEL = 'needs-moderation';

async function run({ github, context }) {
  const payload = context.payload;
  if (payload.sender && payload.sender.type === 'Bot') {
    return;
  }
  const issue = payload.issue;
  const comment = payload.comment;
  const author = (comment || issue).author_association;
  if (['OWNER', 'MEMBER', 'COLLABORATOR'].includes(author)) {
    return;
  }
  const item = comment ? `comment-${comment.id}` : 'issue';
  const text = comment ? comment.body : `${issue.title}\n${issue.body || ''}`;
  const findings = scan(text);
  if (findings.length === 0) {
    return;
  }

  const { owner, repo } = context.repo;
  const marker = `<!-- gaius-issue-guard:${item} -->`;
  const existing = await github.paginate(github.rest.issues.listComments, {
    owner, repo, issue_number: issue.number, per_page: 100,
  });
  await github.rest.issues.addLabels({ owner, repo, issue_number: issue.number, labels: [LABEL] });
  if (existing.some((c) => (c.body || '').includes(marker))) {
    return;
  }
  const where = comment ? `[this comment](${comment.html_url})` : 'this issue';
  await github.rest.issues.createComment({
    owner, repo, issue_number: issue.number,
    body: [
      marker,
      `An automatic check found something in ${where} that may break the [issue rules](https://github.com/${owner}/${repo}/blob/main/CONTRIBUTING.md):`,
      '',
      ...findings.map((f) => `- ${f}`),
      '',
      'Gaius never shares game files, builds or download links. Please edit your post to remove them; a maintainer will review it.',
      '',
      `自动检查发现${comment ? '这条评论' : '此 issue'}可能违反 [issue 规范](https://github.com/${owner}/${repo}/blob/main/CONTRIBUTING.md)。Gaius 不分享游戏文件、构建或下载链接，请编辑删除相关内容，维护者会进行审核。`,
    ].join('\n'),
  });
}

module.exports = { scan, run };
