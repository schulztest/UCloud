function readInt1(buffer: DataView, offset: number): number {
    return buffer.getInt8(offset);
}

function readInt2(buffer: DataView, offset: number): number {
    return buffer.getInt16(offset, false);
}

function readInt4(buffer: DataView, offset: number): number {
    return buffer.getInt32(offset, false);
}

function readInt8(buffer: DataView, offset: number): bigint {
    return buffer.getBigInt64(offset, false);
}

function readBool(buffer: DataView, offset: number): boolean {
    return buffer.getInt8(offset) !== 0;
}

function readBytes(buffer: DataView, offset: number, size: number): Uint8Array {
    return new Uint8Array(buffer.buffer, offset, size);
}

export interface LargeText {
    previewOrContent: string;
    overflowIdentifier?: string;
}

const textDecoder = new TextDecoder();

function readText(buffer: DataView, offset: number, maxSize: number): LargeText {
    const u8a = new Uint8Array(buffer.buffer);
    let slice = u8a.slice(offset, offset + maxSize);
    const endIndex = slice.indexOf(0);
    slice = u8a.slice(offset, offset + endIndex);
    const decodedSlice = textDecoder.decode(slice);
    if (decodedSlice.startsWith("#OF#")) {
        const withoutPrefix = decodedSlice.substring(4);
        const posIdx = withoutPrefix.indexOf("#");
        if (posIdx <= -1) {
            return {previewOrContent: "Invalid blob"};
        }

        const overflowIdentifier = decodedSlice.substring(0, posIdx);
        const previewOrContent = decodedSlice.substring(posIdx + 1);
        return {previewOrContent, overflowIdentifier};
    }

    return {previewOrContent: decodedSlice};
}

export enum BinaryDebugMessageType {
    CLIENT_REQUEST = 1,
    CLIENT_RESPONSE = 2,
    SERVER_REQUEST = 3,
    SERVER_RESPONSE = 4,
    DATABASE_CONNECTION = 5,
    DATABASE_TRANSACTION = 6,
    DATABASE_QUERY = 7,
    DATABASE_RESPONSE = 8,
    LOG = 9,
}

export function binaryDebugMessageTypeToString(type: BinaryDebugMessageType): string {
    return BinaryDebugMessageType[type];
}

export enum MessageImportance {
    TELL_ME_EVERYTHING = 0,
    IMPLEMENTATION_DETAIL = 1,
    THIS_IS_NORMAL = 2,
    THIS_IS_ODD = 3,
    THIS_IS_WRONG = 4,
    THIS_IS_DANGEROUS = 5,
}

export function messageImportanceToString(importance: MessageImportance): string {
    return MessageImportance[importance];
}

export interface BinaryDebugMessage {
    type: BinaryDebugMessageType;
    ctxGeneration: number;
    ctxParent: number;
    ctxId: number;
    timestamp: number;
    importance: MessageImportance;
    id: number;
}

// Header length
const HDRL = 34;
abstract class BaseBinaryDebugMessage implements BinaryDebugMessage {
    protected buffer: DataView;
    protected offset: number;

    constructor(buffer: DataView, offset: number) {
        this.buffer = buffer;
        this.offset = offset;
    }

    get type(): BinaryDebugMessageType {
        // Note(Jonas): Is this correct?
        return BinaryDebugMessageType.CLIENT_REQUEST;
    }

    get typeString(): string {
        return binaryDebugMessageTypeToString(this.type);
    }

    get ctxGeneration(): number {
        return Number(readInt8(this.buffer, this.offset + 1)); // 1
    }

    get ctxParent(): number {
        return readInt4(this.buffer, this.offset + 9); // 1 + 8
    }

    get ctxId(): number {
        return readInt4(this.buffer, this.offset + 13); // 1 + 8 + 4
    }

    get timestamp(): number {
        return Number(readInt8(this.buffer, this.offset + 17)); // 1 + 8 + 4 + 4
    }

    get importance(): MessageImportance {
        return readInt1(this.buffer, this.offset + 25) as MessageImportance; // 1 + 8 + 4 + 4 + 8
    }

    get id(): number {
        return readInt4(this.buffer, this.offset + 26); // 1 + 8 + 4 + 4 + 8 + 4
    }
}

export class Log extends BaseBinaryDebugMessage {
    get type(): BinaryDebugMessageType {
        return BinaryDebugMessageType.LOG;
    }

    get typeString(): string {
        return BinaryDebugMessageType[this.type];
    }

    get message(): LargeText {
        return readText(this.buffer, this.offset + HDRL, 128);
    }

    get extra(): LargeText {
        return readText(this.buffer, this.offset + HDRL + 128, 32);
    }
}

// Contexts
export enum DebugContextType {
    CLIENT_REQUEST = 0,
    SERVER_REQUEST = 1,
    DATABASE_TRANSACTION = 2,
    BACKGROUND_TASK = 3,
    OTHER = 4,
}

export function debugContextToString(ctx: DebugContextType): string {
    return DebugContextType[ctx];
}

export class DebugContext {
    protected buffer: DataView;
    protected offset: number;

    constructor(buffer: DataView, offset: number) {
        this.buffer = buffer;
        this.offset = offset;
    }

    /* Context ID */
    get parent(): number {
        return readInt4(this.buffer, this.offset + 0);
    }

    /* Also Context ID? */
    get id(): number {
        return readInt4(this.buffer, this.offset + 4);
    }

    get importance(): MessageImportance {
        return readInt1(this.buffer, this.offset + 8) as MessageImportance;
    }

    get importanceString(): string {
        return MessageImportance[this.importance];
    }

    get type(): DebugContextType {
        return readInt1(this.buffer, this.offset + 9) as DebugContextType;
    }

    get typeString(): string {
        return debugContextToString(this.type);
    }

    get timestamp(): number {
        return Number(readInt8(this.buffer, this.offset + 10));
    }

    get name(): string {
        return readNameFromBuffer(this.buffer, this.offset + 22, 108);
    }
}

function readNameFromBuffer(buffer: DataView, offset: number, size: number) {
    const nameBytes = readBytes(buffer, offset, size);
    let end = nameBytes.indexOf(0);
    if (end === -1) end = nameBytes.length;
    const slice = nameBytes.slice(0, end);
    return textDecoder.decode(slice);
}

export function getServiceName(buffer: DataView) {
    return readNameFromBuffer(buffer, 8, 264 - 16);
}

export function getGenerationName(buffer: DataView) {
    return readNameFromBuffer(buffer, 264 - 16, 16); 
}