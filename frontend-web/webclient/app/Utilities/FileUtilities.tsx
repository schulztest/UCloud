import {Cloud} from "Authentication/SDUCloudObject";
import SDUCloud from "Authentication/lib";
import {File, MoveCopyOperations, Operation, SortOrder, SortBy, FileType, FileOperation, FileResource} from "Files";
import {Page} from "Types";
import {History} from "history";
import * as UF from "UtilityFunctions";
import {SensitivityLevelMap} from "DefaultObjects";
import {unwrap, isError, ErrorMessage} from "./XHRUtils";
import {UploadPolicy} from "Uploader/api";
import {SnackType} from "Snackbar/Snackbars";
import {addStandardDialog, rewritePolicyDialog, shareDialog, sensitivityDialog} from "UtilityComponents";
import {dialogStore} from "Dialog/DialogStore";
import {snackbarStore} from "Snackbar/SnackbarStore";

function initialSetup(operations: MoveCopyOperations) {
    resetFileSelector(operations);
    return {failurePaths: [] as string[], applyToAll: false, policy: UploadPolicy.REJECT as UploadPolicy};
}

function getNewPath(newParentPath: string, currentPath: string): string {
    return `${UF.removeTrailingSlash(resolvePath(newParentPath))}/${getFilenameFromPath(resolvePath(currentPath))}`
}

enum CopyOrMove {
    Move,
    Copy
}

interface CopyOrMoveFiles {
    operation: CopyOrMove
    files: File[]
    copyMoveOps: MoveCopyOperations
    cloud: SDUCloud
    setLoading: () => void
}

export function copyOrMoveFiles({operation, files, copyMoveOps, cloud, setLoading}: CopyOrMoveFiles): void {
    const copyOrMoveQuery = operation === CopyOrMove.Copy ? copyFileQuery : moveFileQuery;
    let successes = 0, failures = 0, pathToFetch = "";
    copyMoveOps.showFileSelector(true);
    copyMoveOps.setDisallowedPaths([getParentPath(files[0].path)].concat(files.map(f => f.path)));
    copyMoveOps.setFileSelectorCallback(async (targetPathFolder: File) => {
        let {failurePaths, applyToAll, policy} = initialSetup(copyMoveOps);
        for (let i = 0; i < files.length; i++) {
            let f = files[i];
            let {exists, allowRewrite, newPathForFile} = await moveCopySetup({
                targetPath: targetPathFolder.path,
                path: f.path,
                cloud
            });
            if (exists && !applyToAll) {
                const result = await rewritePolicyDialog({
                    path: newPathForFile,
                    homeFolder: cloud.homeFolder,
                    filesRemaining: files.length - i
                });
                if (result != false) {
                    allowRewrite = true;
                    allowRewrite = !!result.policy;
                    policy = result.policy as UploadPolicy;
                    if (files.length - i > 1) applyToAll = result.applyToAll;
                }
            }
            if (applyToAll) allowRewrite = true;
            if ((exists && allowRewrite) || !exists) {
                const request = await cloud.post(copyOrMoveQuery(f.path, newPathForFile, policy))
                if (UF.inSuccessRange(request.request.status)) {
                    successes++;
                    pathToFetch = newPathForFile;
                    if (request.request.status === 202) snackbarStore.addSnack({
                        message: `Operation for ${f.path} is in progress.`,
                        type: SnackType.Success
                    })
                } else {
                    failures++;
                    failurePaths.push(getFilenameFromPath(f.path))
                    snackbarStore.addSnack({
                        message: `An error occurred ${operation === CopyOrMove.Copy ? "copying" : "moving"} ${f.path}`,
                        type: SnackType.Failure
                    });
                }
            }
        }
        if (successes) {
            setLoading();
            if (policy === UploadPolicy.RENAME) copyMoveOps.fetchFilesPage(getParentPath(pathToFetch));
            else copyMoveOps.fetchPageFromPath(pathToFetch);
        }
        if (!failures && successes) onOnlySuccess({
            operation: operation === CopyOrMove.Copy ? "Copied" : "Moved",
            fileCount: files.length
        });
        else if (failures)
            snackbarStore.addSnack({
                message: `Failed to ${operation === CopyOrMove.Copy ? "copy" : "move"} files: ${failurePaths.join(", ")}`,
                type: SnackType.Failure
            });
    });
};

interface MoveCopySetup {
    targetPath: string
    path: string
    cloud: SDUCloud
}

async function moveCopySetup({targetPath, path, cloud}: MoveCopySetup) {
    const newPathForFile = getNewPath(targetPath, path);
    const exists = await checkIfFileExists(newPathForFile, cloud);
    return {exists, allowRewrite: false, newPathForFile};
}

interface OnOnlySuccess {
    operation: string
    fileCount: number
}

function onOnlySuccess({operation, fileCount}: OnOnlySuccess) {
    snackbarStore.addSnack({message: `${operation} ${fileCount} files`, type: SnackType.Success});
}

interface ResetFileSelector {
    showFileSelector: (v: boolean) => void
    setFileSelectorCallback: (v: any) => void
    setDisallowedPaths: (array: string[]) => void;
}

function resetFileSelector(operations: ResetFileSelector) {
    operations.showFileSelector(false);
    operations.setFileSelectorCallback(undefined);
    operations.setDisallowedPaths([]);
}

export const checkIfFileExists = async (path: string, cloud: SDUCloud): Promise<boolean> => {
    try {
        await cloud.get(statFileQuery(path))
        return true;
    } catch (e) {
        // FIXME: in the event of other than 404
        return !(e.request.status === 404);
    }
};

export const startRenamingFiles = (files: File[], page: Page<File>) => {
    const paths = files.map(it => it.path);
    page.items.forEach(file => {
        if (paths.includes(file.path)) file.beingRenamed = true
    });
    return page;
};

export type AccessRight = "READ" | "WRITE" | "EXECUTE";

function hasAccess(accessRight: AccessRight, file: File) {
    const username = Cloud.activeUsername;
    if (file.ownerName === username) return true;
    if (file.acl === null) return true; // If ACL is null, we are still fetching the ACL

    const relevantEntries = file.acl.filter(item => !item.group && item.entity === username);
    return relevantEntries.some(entry => entry.rights.includes(accessRight));
}

export const allFilesHasAccessRight = (accessRight: AccessRight, files: File[]) =>
    files.every(f => hasAccess(accessRight, f));


interface CreateFileLink {
    file: File
    cloud: SDUCloud
    setLoading: () => void
    fetchPageFromPath: (p: string) => void
}

export async function createFileLink({file, cloud, setLoading, fetchPageFromPath}: CreateFileLink) {
    const fileName = getFilenameFromPath(file.path);
    const linkPath = file.path.replace(fileName, `Link to ${fileName} `);
    setLoading();
    try {
        await cloud.post("/files/create-link", {
            linkTargetPath: file.path,
            linkPath
        });
        fetchPageFromPath(linkPath);
    } catch {
        snackbarStore.addSnack({message: "An error occurred creating link.", type: SnackType.Failure});
    }
}

interface StateLessOperations {
    setLoading: () => void
    onSensitivityChange?: () => void
}

/**
 * @returns Stateless operations for files
 */
export const StateLessOperations = ({setLoading, onSensitivityChange}: StateLessOperations): Operation[] => [
    {
        text: "Share",
        onClick: (files: File[], cloud: SDUCloud) => shareFiles({files, cloud}),
        disabled: (files: File[], cloud: SDUCloud) => !allFilesHasAccessRight("WRITE", files) || !allFilesHasAccessRight("READ", files),
        icon: "share",
        color: undefined
    },
    {
        text: "Download",
        onClick: (files: File[], cloud: SDUCloud) => downloadFiles(files, setLoading, cloud),
        disabled: (files: File[], cloud: SDUCloud) => !UF.downloadAllowed(files) || !allFilesHasAccessRight("READ", files),
        icon: "download",
        color: undefined
    },
    {
        text: "Sensitivity",
        onClick: (files: File[], cloud: SDUCloud) => updateSensitivity({files, cloud, onSensitivityChange}),
        disabled: (files: File[], cloud: SDUCloud) => false,
        icon: "verified",
        color: undefined
    },
    {
        text: "Copy Path",
        onClick: (files: File[], cloud: SDUCloud) => UF.copyToClipboard({
            value: files[0].path,
            message: `${replaceHomeFolder(files[0].path, cloud.homeFolder)} copied to clipboard`
        }),
        disabled: (files: File[], cloud: SDUCloud) => !UF.inDevEnvironment() || files.length !== 1,
        icon: "chat",
        color: undefined
    }
];

interface CreateLinkOperation {
    fetchPageFromPath: (p: string) => void
    setLoading: () => void
}

export const CreateLinkOperation = ({fetchPageFromPath, setLoading}: CreateLinkOperation) => [{
    text: "Create link",
    onClick: (files: File[], cloud: SDUCloud) => createFileLink({
        file: files[0],
        cloud,
        setLoading,
        fetchPageFromPath
    }),
    disabled: (files: File[], cloud: SDUCloud) => files.length > 1,
    icon: "link",
    color: undefined
}];

interface FileSelectorOperations {
    fileSelectorOperations: MoveCopyOperations
    setLoading: () => void
}

/**
 * @returns Move and Copy operations for files
 */
export const FileSelectorOperations = ({fileSelectorOperations, setLoading}: FileSelectorOperations): Operation[] => [
    {
        text: "Copy",
        onClick: (files: File[], cloud: SDUCloud) => copyOrMoveFiles({
            operation: CopyOrMove.Copy,
            files,
            copyMoveOps: fileSelectorOperations,
            cloud,
            setLoading
        }),
        disabled: (files: File[], cloud: SDUCloud) => !allFilesHasAccessRight("WRITE", files),
        icon: "copy",
        color: undefined
    },
    {
        text: "Move",
        onClick: (files: File[], cloud: SDUCloud) => copyOrMoveFiles({
            operation: CopyOrMove.Move,
            files,
            copyMoveOps: fileSelectorOperations,
            cloud,
            setLoading
        }),
        disabled: (files: File[], cloud: SDUCloud) => !allFilesHasAccessRight("WRITE", files) || files.some(f => isFixedFolder(f.path, cloud.homeFolder)),
        icon: "move",
        color: undefined
    }
];

export const archiveExtensions: string[] = [".tar.gz", ".zip"];
export const isArchiveExtension = (fileName: string): boolean => archiveExtensions.some(it => fileName.endsWith(it));


interface ExtractionOperation {
    onFinished: () => void
}

/**
 *
 * @param onFinished called when extraction is completed successfully.
 */
export const ExtractionOperation = ({onFinished}: ExtractionOperation): Operation[] => [
    {
        text: "Extract archive",
        onClick: (files, cloud) => extractArchive({files, cloud, onFinished}),
        disabled: (files, cloud) => !files.every(it => isArchiveExtension(it.path)),
        icon: "open",
        color: undefined
    }
];


interface MoveFileToTrashOperation {
    onMoved: () => void
    setLoading: () => void
}

/**
 *
 * @param onMoved To be called on completed deletion of files
 * @returns the Delete operation in an array
 */
export const MoveFileToTrashOperation = ({onMoved, setLoading}: MoveFileToTrashOperation): Operation[] => [
    {
        text: "Move to Trash",
        onClick: (files, cloud) => moveToTrash({files, cloud, setLoading, callback: onMoved}),
        disabled: (files: File[], cloud: SDUCloud) => (!allFilesHasAccessRight("WRITE", files) || files.some(f => isFixedFolder(f.path, cloud.homeFolder)) || files.every(({path}) => inTrashDir(path, cloud))),
        icon: "trash",
        color: "red"
    },
    {
        text: "Delete Files",
        onClick: (files, cloud) => batchDeleteFiles({files, cloud, setLoading, callback: onMoved}),
        disabled: (files, cloud) => !files.every(f => getParentPath(f.path) === cloud.trashFolder),
        icon: "trash",
        color: "red"
    }
];

export const ClearTrashOperations = (toHome: () => void): Operation[] => [{
    text: "Clear Trash",
    onClick: (files, cloud) => clearTrash({cloud, callback: toHome}),
    disabled: (files, cloud) => !files.every(f => UF.addTrailingSlash(f.path) === cloud.trashFolder) && !files.every(f => getParentPath(f.path) === cloud.trashFolder),
    icon: "trash",
    color: "red"
}];

/**
 * @returns Properties and Project Operations for files.
 */
export const HistoryFilesOperations = (history: History): FileOperation[] => {
    const ops: FileOperation[] = [{
        text: "Properties",
        onClick: (files: File[], cloud: SDUCloud) => history.push(fileInfoPage(files[0].path)),
        disabled: (files: File[], cloud: SDUCloud) => files.length !== 1,
        icon: "properties", color: "blue"
    }];
    return ops;
};

export const fileInfoPage = (path: string): string => `/files/info?path=${encodeURIComponent(resolvePath(path))}`;
export const filePreviewPage = (path: string) => `/files/preview?path=${encodeURIComponent(resolvePath(path))}`;
export const fileTablePage = (path: string): string => `/files?path=${encodeURIComponent(resolvePath(path))}`;


interface AllFileOperations {
    stateless?: boolean,
    fileSelectorOperations?: MoveCopyOperations
    onDeleted?: () => void
    onExtracted?: () => void
    onClearTrash?: () => void
    onSensitivityChange?: () => void
    history?: History,
    setLoading: () => void
}

export function allFileOperations(
    {
        stateless,
        fileSelectorOperations,
        onDeleted,
        onExtracted,
        onClearTrash,
        history,
        setLoading,
        onSensitivityChange,
    }: AllFileOperations
) {
    const stateLessOperations = stateless ? StateLessOperations({setLoading, onSensitivityChange}) : [];
    const fileSelectorOps = !!fileSelectorOperations ? FileSelectorOperations({
        fileSelectorOperations,
        setLoading
    }) : [];
    const deleteOperation = !!onDeleted ? MoveFileToTrashOperation({onMoved: onDeleted, setLoading}) : [];
    const clearTrash = !!onClearTrash ? ClearTrashOperations(onClearTrash) : [];
    const historyOperations = !!history ? HistoryFilesOperations(history) : [];
    const extractionOperations = !!onExtracted ? ExtractionOperation({onFinished: onExtracted}) : [];
    return [
        ...stateLessOperations,
        ...fileSelectorOps,
        ...deleteOperation,
        ...extractionOperations,        // ...clearTrash,
        ...historyOperations,
    ];
}

export function addFileAcls(withoutAcls: Page<File>, withAcls: Page<File>): Page<File> {
    withoutAcls.items.forEach(file => {
        const id = file.fileId;
        const otherFile = withAcls.items.find(it => it.fileId === id);
        if (otherFile) {
            file.acl = otherFile.acl;
            file.favorited = otherFile.favorited;
            file.ownerName = otherFile.ownerName;
            file.sensitivityLevel = otherFile.sensitivityLevel;
        }
    });
    return withoutAcls;
}

export function markFileAsChecked(path: string, page: Page<File>) {
    const resolvedPath = resolvePath(path);
    const i = page.items.findIndex(it => it.path === resolvedPath);
    page.items[i].isChecked = true;
    return page;
}

/**
 * Used for resolving paths, which contain either "." or "..", and returning the resolved path.
 * @param path The current input path, which can include relative paths
 */
export function resolvePath(path: string) {
    const components = path.split("/");
    const result: string[] = [];
    components.forEach(it => {
        if (it === ".") {
            return;
        } else if (it === "..") {
            result.pop();
        } else {
            result.push(it);
        }
    });
    return result.join("/");
}

const toAttributesString = (attrs: FileResource[]) =>
    attrs.length > 0 ? `&attributes=${encodeURIComponent(attrs.join(","))}` : "";

export const filepathQuery = (path: string, page: number, itemsPerPage: number, order: SortOrder = SortOrder.ASCENDING, sortBy: SortBy = SortBy.PATH, attrs: FileResource[] = []): string =>
    `files?path=${encodeURIComponent(resolvePath(path))}&itemsPerPage=${itemsPerPage}&page=${page}&order=${encodeURIComponent(order)}&sortBy=${encodeURIComponent(sortBy)}${toAttributesString(attrs)}`;

// FIXME: UF.removeTrailingSlash(path) shouldn't be unnecessary, but otherwise causes backend issues
export const fileLookupQuery = (path: string, itemsPerPage: number = 25, order: SortOrder = SortOrder.DESCENDING, sortBy: SortBy = SortBy.PATH, attrs: FileResource[]): string =>
    `files/lookup?path=${encodeURIComponent(resolvePath(path))}&itemsPerPage=${itemsPerPage}&order=${encodeURIComponent(order)}&sortBy=${encodeURIComponent(sortBy)}${toAttributesString(attrs)}`;

export const advancedFileSearch = "/file-search/advanced";

export const recentFilesQuery = "/files/stats/recent";

export function moveFileQuery(path: string, newPath: string, policy?: UploadPolicy): string {
    let query = `/files/move?path=${encodeURIComponent(resolvePath(path))}&newPath=${encodeURIComponent(newPath)}`;
    if (policy) query += `&policy=${policy}`;
    return query;
}

export function copyFileQuery(path: string, newPath: string, policy: UploadPolicy): string {
    let query = `/files/copy?path=${encodeURIComponent(resolvePath(path))}&newPath=${encodeURIComponent(newPath)}`;
    if (policy) query += `&policy=${policy}`;
    return query;
}

export const statFileQuery = (path: string): string => `/files/stat?path=${encodeURIComponent(path)}`;
export const favoritesQuery = (page: number = 0, itemsPerPage: number = 25): string =>
    `/files/favorite?page=${page}&itemsPerPage=${itemsPerPage}`;

export const newMockFolder = (path: string = "", beingRenamed: boolean = true): File => ({
    fileType: "DIRECTORY",
    path,
    creator: "Creator",
    fileId: "",
    ownSensitivityLevel: null,
    createdAt: new Date().getTime(),
    modifiedAt: new Date().getTime(),
    ownerName: "",
    size: 0,
    acl: [],
    favorited: false,
    sensitivityLevel: SensitivityLevelMap.PRIVATE,
    isChecked: false,
    beingRenamed,
    link: false,
    isMockFolder: true
});

interface IsInvalidPathname {
    path: string
    filePaths: string[]
}

/**
 * Checks if a pathname is legal/already in use
 * @param {string} path The path being tested
 * @param {string[]} filePaths the other file paths path is being compared against
 * @param {() => void} addSnack used to add a message to SnackBar
 * @returns whether or not the path is invalid
 */
export const isInvalidPathName = ({path, filePaths}: IsInvalidPathname): boolean => {
    if (["..", "/"].some((it) => path.includes(it))) {
        snackbarStore.addSnack({message: "Folder name cannot contain '..' or '/'", type: SnackType.Failure});
        return true
    }
    if (path === "" || path === ".") {
        snackbarStore.addSnack({message: "Folder name cannot be empty or be \".\"", type: SnackType.Failure});
        return true;
    }
    const existingName = filePaths.some(it => it === path);
    if (existingName) {
        snackbarStore.addSnack({message: "File with that name already exists", type: SnackType.Failure});
        return true;
    }
    return false;
};

/**
 * Checks if the specific folder is a fixed folder, meaning it can not be removed, renamed, deleted, etc.
 * @param {string} filePath the path of the file to be checked
 * @param {string} homeFolder the path for the homefolder of the current user
 */
export const isFixedFolder = (filePath: string, homeFolder: string): boolean => {
    return [ // homeFolder contains trailing slash
        `${homeFolder}Favorites`,
        `${homeFolder}Jobs`,
        `${homeFolder}Trash`
    ].some(it => UF.removeTrailingSlash(it) === filePath)
};

export const favoriteFileFromPage = (page: Page<File>, filesToFavorite: File[], cloud: SDUCloud): Page<File> => {
    filesToFavorite.forEach(f => {
        const i = page.items.findIndex(file => file.path === f.path)!;
        favoriteFile(page.items[i], cloud);
    });
    return page;
};

/**
 * Used to favorite/defavorite a file based on its current state.
 * @param {File} file The single file to be favorited
 * @param {Cloud} cloud The cloud instance used to changed the favorite state for the file
 */
export const favoriteFile = (file: File, cloud: SDUCloud): File => {
    try {
        cloud.post(favoriteFileQuery(file.path), {})
    } catch (e) {
        UF.errorMessageOrDefault(e, "An error occurred favoriting file.");
    }
    file.favorited = !file.favorited;
    return file;
};

/**
 * Used to favorite/defavorite a file based on its current state.
 * @param {File} file The single file to be favorited
 * @param {Cloud} cloud The cloud instance used to changed the favorite state for the file
 */
export const favoriteFileAsync = async (file: File, cloud: SDUCloud): Promise<File> => {
    try {
        await cloud.post(favoriteFileQuery(file.path), {})
    } catch (e) {
        UF.errorMessageOrDefault(e, "An error occurred favoriting file.");
    }
    file.favorited = !file.favorited;
    return file;
};

const favoriteFileQuery = (path: string) => `/files/favorite?path=${encodeURIComponent(path)}`;

interface ReclassifyFile {
    file: File
    sensitivity: SensitivityLevelMap
    cloud: SDUCloud
}

export const reclassifyFile = async ({file, sensitivity, cloud}: ReclassifyFile): Promise<File> => {
    const serializedSensitivity = sensitivity === SensitivityLevelMap.INHERIT ? null : sensitivity;
    const callResult = await unwrap(cloud.post<void>("/files/reclassify", {
        path: file.path,
        sensitivity: serializedSensitivity
    }));
    if (isError(callResult)) {
        snackbarStore.addSnack({message: (callResult as ErrorMessage).errorMessage, type: SnackType.Failure})
        return file;
    }
    return {...file, sensitivityLevel: sensitivity, ownSensitivityLevel: sensitivity};
};

export const canBeProject = (files: File[], homeFolder: string): boolean =>
    files.length === 1 && files.every(f => isDirectory(f)) && !isFixedFolder(files[0].path, homeFolder) && !isLink(files[0]);

export const previewSupportedExtension = (path: string) => false;

export const toFileText = (selectedFiles: File[]): string =>
    `${selectedFiles.length} file${selectedFiles.length > 1 ? "s" : ""} selected`

export const isLink = (file: File) => file.link;
export const isDirectory = (file: { fileType: FileType }): boolean => file.fileType === "DIRECTORY";
export const replaceHomeFolder = (path: string, homeFolder: string): string => path.replace(homeFolder, "Home/");
export const expandHomeFolder = (path: string, homeFolder: string): string => {
    if (path.startsWith("Home/"))
        return path.replace("Home/", homeFolder);
    return path;
};

const extractFilesQuery = "/files/extract";

interface ExtractArchive {
    files: File[]
    cloud: SDUCloud
    onFinished: () => void
}

export const extractArchive = ({files, cloud, onFinished}: ExtractArchive): void => {
    files.forEach(async f => {
        try {
            await cloud.post(extractFilesQuery, {path: f.path});
            snackbarStore.addSnack({message: "File extracted", type: SnackType.Success});
        } catch (e) {
            snackbarStore.addSnack({
                message: UF.errorMessageOrDefault(e, "An error occurred extracting the file."),
                type: SnackType.Failure
            });
        }
    });
    onFinished();
};


export const clearTrash = ({cloud, callback}: { cloud: SDUCloud, callback: () => void }) =>
    clearTrashDialog({
        onConfirm: async () => {
            await cloud.post("/files/trash/clear", {});
            callback();
            dialogStore.popDialog();
        },
        onCancel: () => dialogStore.popDialog()
    });

export const getParentPath = (path: string): string => {
    if (path.length === 0) return path;
    let splitPath = path.split("/");
    splitPath = splitPath.filter(path => path);
    let parentPath = "/";
    for (let i = 0; i < splitPath.length - 1; i++) {
        parentPath += splitPath[i] + "/";
    }
    return parentPath;
};

const goUpDirectory = (count: number, path: string): string => count ? goUpDirectory(count - 1, getParentPath(path)) : path;

const toFileName = (path: string): string => {
    const lastSlash = path.lastIndexOf("/");
    if (lastSlash !== -1 && path.length > lastSlash + 1) {
        return path.substring(lastSlash + 1);
    } else {
        return path;
    }
};

export function getFilenameFromPath(path: string): string {
    const replacedHome = replaceHomeFolder(path, Cloud.homeFolder);
    const fileName = toFileName(replacedHome);
    if (fileName === "..") return `.. (${toFileName(goUpDirectory(2, replacedHome))})`;
    if (fileName === ".") return `. (Current folder)`;
    return fileName;
}

export function downloadFiles(files: File[], setLoading: () => void, cloud: SDUCloud) {
    files.map(f => f.path).forEach(p =>
        cloud.createOneTimeTokenWithPermission("files.download:read").then((token: string) => {
            const element = document.createElement("a");
            element.setAttribute("href", `/api/files/download?path=${encodeURIComponent(p)}&token=${encodeURIComponent(token)}`);
            element.style.display = "none";
            document.body.appendChild(element);
            element.click();
            document.body.removeChild(element);
        }));
}

interface UpdateSensitivity {
    files: File[]
    cloud: SDUCloud
    onSensitivityChange?: () => void
}

async function updateSensitivity({files, cloud, onSensitivityChange}: UpdateSensitivity) {
    const input = await sensitivityDialog();
    if ("cancelled" in input) return;
    try {
        await Promise.all(files.map(file => reclassifyFile({file, sensitivity: input.option, cloud})));
    } catch (e) {
        snackbarStore.addSnack({
            message: UF.errorMessageOrDefault(e, "Could not reclassify file"),
            type: SnackType.Failure
        })
    } finally {
        if (!!onSensitivityChange) onSensitivityChange();
    }
}

export const fetchFileContent = async (path: string, cloud: SDUCloud): Promise<Response> => {
    const token = await cloud.createOneTimeTokenWithPermission("files.download:read");
    return fetch(`/api/files/download?path=${encodeURIComponent(path)}&token=${encodeURIComponent(token)}`)
};

export const sizeToString = (bytes: number | null): string => {
    if (bytes === null) return "";
    if (bytes < 0) return "Invalid size";
    if (bytes < 1000) {
        return `${bytes} B`;
    } else if (bytes < 1000 ** 2) {
        return `${(bytes / 1000).toFixed(2)} KB`;
    } else if (bytes < 1000 ** 3) {
        return `${(bytes / 1000 ** 2).toFixed(2)} MB`;
    } else if (bytes < 1000 ** 4) {
        return `${(bytes / 1000 ** 3).toFixed(2)} GB`;
    } else if (bytes < 1000 ** 5) {
        return `${(bytes / 1000 ** 4).toFixed(2)} TB`;
    } else if (bytes < 1000 ** 6) {
        return `${(bytes / 1000 ** 5).toFixed(2)} PB`;
    } else {
        return `${(bytes / 1000 ** 6).toFixed(2)} EB`;
    }
};

interface ShareFiles {
    files: File[],
    cloud: SDUCloud
}

export const shareFiles = async ({files, cloud}: ShareFiles) => {
    const input = await shareDialog();
    if ("cancelled" in input) return;
    const rights: string[] = [];
    if (input.readOrEdit.includes("read")) rights.push("READ");
    if (input.readOrEdit.includes("read_edit")) rights.push("WRITE");
    let iteration = 0;
    // Replace with Promise.all
    files.map(f => f.path).forEach((path, _, paths) => {
        const body = {
            sharedWith: input.username,
            path,
            rights
        };
        cloud.put(`/shares/`, body)
            .then(() => {
                if (++iteration === paths.length) snackbarStore.addSnack({
                    message: "Files shared successfully",
                    type: SnackType.Success
                })
            })
            .catch(({response}) => snackbarStore.addSnack({message: `${response.why}`, type: SnackType.Failure}));
    });
};

const moveToTrashDialog = ({filePaths, onCancel, onConfirm}: { onConfirm: () => void, onCancel: () => void, filePaths: string[] }): void => {
    const message = filePaths.length > 1 ? `Move ${filePaths.length} files to trash?` :
        `Move file ${getFilenameFromPath(filePaths[0])} to trash?`;

    addStandardDialog({
        title: "Move files to trash",
        message,
        onConfirm,
        confirmText: "Move files"
    });
};

export function clearTrashDialog({onConfirm, onCancel}: { onConfirm: () => void, onCancel: () => void }): void {
    addStandardDialog({
        title: "Empty trash?",
        message: "",
        confirmText: "Confirm",
        cancelText: "Cancel",
        onConfirm
    })
}

interface ResultToNotification {
    failures: string[]
    paths: string[]
    homeFolder: string
}

function resultToNotification({failures, paths, homeFolder}: ResultToNotification) {
    const successMessage = successResponse(paths, homeFolder);
    if (failures.length === 0) {
        snackbarStore.addSnack({message: successMessage, type: SnackType.Success});
    } else if (failures.length === paths.length) {
        snackbarStore.addSnack({
            message: "Failed moving all files, please try again later",
            type: SnackType.Failure
        });
    } else {
        snackbarStore.addSnack({
            message: `${successMessage}\n Failed to move files: ${failures.join(", ")}`,
            type: SnackType.Information
        });
    }
}

const successResponse = (paths: string[], homeFolder: string) =>
    paths.length > 1 ? `${paths.length} files moved to trash.` : `${replaceHomeFolder(paths[0], homeFolder)} moved to trash`;

type Failures = { failures: string[] }

interface MoveToTrash {
    files: File[]
    cloud: SDUCloud
    setLoading: () => void
    callback: () => void
}

export const moveToTrash = ({files, cloud, setLoading, callback}: MoveToTrash) => {
    const paths = files.map(f => f.path);
    moveToTrashDialog({
        filePaths: paths, onConfirm: async () => {
            try {
                setLoading();
                const {response} = await cloud.post<Failures>("/files/trash/", {files: paths});
                resultToNotification({failures: response.failures, paths, homeFolder: cloud.homeFolder});
                callback();
            } catch (e) {
                snackbarStore.addSnack({message: e.why, type: SnackType.Failure});
                callback();
            }
        },
        onCancel: () => undefined
    });
};

interface BatchDeleteFiles {
    files: File[]
    cloud: SDUCloud
    setLoading: () => void
    callback: () => void
}

export const batchDeleteFiles = ({files, cloud, setLoading, callback}: BatchDeleteFiles) => {
    const paths = files.map(f => f.path);
    const message = paths.length > 1 ? `Delete ${paths.length} files?` :
        `Delete file ${getFilenameFromPath(paths[0])}`;
    addStandardDialog({
        title: "Delete files",
        message,
        confirmText: "Delete files",
        onConfirm: async () => {
            setLoading();
            const promises: { status?: number, response?: string }[] =
                await Promise.all(paths.map(path => cloud.delete("/files", {path}))).then(it => it).catch(it => it);
            const failures = promises.filter(it => it.status).length;
            if (failures > 0) {
                snackbarStore.addSnack({
                    message: promises.filter(it => it.response).map(it => it).join(", "),
                    type: SnackType.Failure
                });
            } else {
                snackbarStore.addSnack({message: "Files deleted", type: SnackType.Success});
            }
            callback();
        }
    });
};

interface MoveFile {
    oldPath: string
    newPath: string
    cloud: SDUCloud
    setLoading: () => void
    onSuccess: () => void
}

export async function moveFile({oldPath, newPath, cloud, setLoading, onSuccess}: MoveFile): Promise<void> {
    setLoading();
    try {
        await cloud.post(`/files/move?path=${encodeURIComponent(oldPath)}&newPath=${encodeURIComponent(newPath)}`);
        onSuccess();
    } catch {
        snackbarStore.addSnack({message: "An error ocurred trying to rename the file.", type: SnackType.Failure});
    }
}

interface CreateFolder {
    path: string
    cloud: SDUCloud
    onSuccess: () => void
}

export async function createFolder({path, cloud, onSuccess}: CreateFolder): Promise<void> {
    try {
        await cloud.post("/files/directory", {path});
        onSuccess();
        snackbarStore.addSnack({message: "Folder created", type: SnackType.Success});
    } catch (e) {
        snackbarStore.addSnack({
            message: UF.errorMessageOrDefault(e, "An error occurred trying to creating the file."),
            type: SnackType.Failure
        });
    }
}

const inTrashDir = (path: string, cloud: SDUCloud): boolean => getParentPath(path) === cloud.trashFolder;
