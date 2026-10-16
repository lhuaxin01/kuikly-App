import { hapTasks } from '@ohos/hvigor-ohos-plugin';
import { kuiklyCompilePlugin } from 'kuikly-ohos-compile-plugin';

export default {
    system: hapTasks,  /* Built-in plugin of Hvigor. It cannot be modified. */
    plugins:[kuiklyCompilePlugin()]         /* Custom plugin to extend the functionality of Hvigor. */
}
