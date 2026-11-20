"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.MAX_ENTRY_BYTES = void 0;
exports.readZipEntries = readZipEntries;
/**
 * Reading entries of a zip archive such as a jar, without a library: the central directory gives
 * every entry's name and where its data starts; stored data is taken as it is, deflated data is
 * inflated with `zlib`. Only the end of the archive, its central directory and the accepted entries
 * are read, not the whole file. Does not use `vscode`, so that it can be tested with `node --test`.
 */
const fs = __importStar(require("fs"));
const zlib = __importStar(require("zlib"));
const END_OF_CENTRAL_DIRECTORY = 0x06054b50;
const CENTRAL_DIRECTORY_ENTRY = 0x02014b50;
const LOCAL_FILE_HEADER = 0x04034b50;
const STORED = 0;
const DEFLATED = 8;
const ENCRYPTED_FLAG = 0x1;
/** The end record is 22 bytes plus a comment of at most 65535 bytes. */
const MAX_END_RECORD_SEARCH = 22 + 0xffff;
/** Zip64 archives mark sizes and offsets they do not fit with all bits set. */
const ZIP64_MARK = 0xffffffff;
/** The largest entry that is extracted; metamodels are far smaller. */
exports.MAX_ENTRY_BYTES = 32 * 1024 * 1024;
function read(descriptor, position, length) {
    const buffer = Buffer.alloc(length);
    const got = fs.readSync(descriptor, buffer, 0, length, position);
    if (got !== length)
        throw new Error('unexpected end of the zip archive');
    return buffer;
}
/** The central directory: where it starts, how long it is, and how many entries it has. */
function centralDirectory(descriptor, size) {
    const tailLength = Math.min(size, MAX_END_RECORD_SEARCH);
    const tail = read(descriptor, size - tailLength, tailLength);
    for (let i = tail.length - 22; i >= 0; i--) {
        if (tail.readUInt32LE(i) === END_OF_CENTRAL_DIRECTORY) {
            const length = tail.readUInt32LE(i + 12);
            const offset = tail.readUInt32LE(i + 16);
            if (offset === ZIP64_MARK || length === ZIP64_MARK) {
                throw new Error('zip64 archives are not supported');
            }
            return { offset, length, count: tail.readUInt16LE(i + 10) };
        }
    }
    throw new Error('not a zip archive: no end of central directory');
}
/**
 * The entries of the archive whose names the filter accepts, with their content.
 *
 * @throws Error if the file is not a zip archive, is a zip64 archive, or an accepted entry is
 *     encrypted, compressed other than by deflate or larger than {@link MAX_ENTRY_BYTES}
 */
function readZipEntries(archivePath, accept) {
    const descriptor = fs.openSync(archivePath, 'r');
    try {
        const directory = centralDirectory(descriptor, fs.fstatSync(descriptor).size);
        const central = read(descriptor, directory.offset, directory.length);
        const entries = [];
        let offset = 0;
        for (let i = 0; i < directory.count; i++) {
            if (central.readUInt32LE(offset) !== CENTRAL_DIRECTORY_ENTRY) {
                throw new Error(`corrupt zip archive: ${archivePath}`);
            }
            const flags = central.readUInt16LE(offset + 8);
            const method = central.readUInt16LE(offset + 10);
            const compressedSize = central.readUInt32LE(offset + 20);
            const size = central.readUInt32LE(offset + 24);
            const nameLength = central.readUInt16LE(offset + 28);
            const extraLength = central.readUInt16LE(offset + 30);
            const commentLength = central.readUInt16LE(offset + 32);
            const localHeader = central.readUInt32LE(offset + 42);
            const name = central.toString('utf-8', offset + 46, offset + 46 + nameLength);
            offset += 46 + nameLength + extraLength + commentLength;
            if (name.endsWith('/') || !accept(name)) {
                continue;
            }
            if (flags & ENCRYPTED_FLAG)
                throw new Error(`encrypted entry ${name} in ${archivePath}`);
            if (size > exports.MAX_ENTRY_BYTES || compressedSize > exports.MAX_ENTRY_BYTES) {
                throw new Error(`entry ${name} in ${archivePath} is larger than ${exports.MAX_ENTRY_BYTES} bytes`);
            }
            const header = read(descriptor, localHeader, 30);
            if (header.readUInt32LE(0) !== LOCAL_FILE_HEADER) {
                throw new Error(`corrupt zip archive: ${archivePath}`);
            }
            const dataStart = localHeader + 30 + header.readUInt16LE(26) + header.readUInt16LE(28);
            const raw = read(descriptor, dataStart, compressedSize);
            if (method === STORED) {
                entries.push({ name, data: raw });
            }
            else if (method === DEFLATED) {
                entries.push({ name, data: zlib.inflateRawSync(raw, { maxOutputLength: exports.MAX_ENTRY_BYTES }) });
            }
            else {
                throw new Error(`unsupported compression ${method} of ${name} in ${archivePath}`);
            }
        }
        return entries;
    }
    finally {
        fs.closeSync(descriptor);
    }
}
//# sourceMappingURL=zipEntries.js.map